package com.sairo.be.domain.settlement.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.settlement.dto.response.EntityLifecycleCorrectionResponse;
import com.sairo.be.domain.settlement.repository.SettlementSourceRepository;
import com.sairo.be.domain.settlement.repository.SettlementSourceRepository.EntityState;
import com.sairo.be.domain.settlement.repository.SettlementSourceRepository.LifecycleEvent;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 매물·고객 수명 사건의 효력시각 오입력 정정이다. 원래 사건은 고치지 않고 새 효력시각·사유·대체 대상을 가진 행을 더한다. 사건 순서가 깨지거나 현재 보관 여부·카테고리가
 * 바뀌는 정정은 막는다.
 */
@Profile("!migrate")
@Service
public class LifecycleCorrectionService {

  static final int REASON_MAX = 500;
  private static final Set<String> KEYS = Set.of("effectiveAt", "correctionReason");
  private static final Set<String> HOLDING = Set.of("CREATED", "ARCHIVED", "RESTORED");

  private final SettlementSourceRepository sourceRepository;
  private final SettlementCalculator calculator;
  private final Clock clock;

  public LifecycleCorrectionService(
      SettlementSourceRepository sourceRepository, SettlementCalculator calculator, Clock clock) {
    this.sourceRepository = sourceRepository;
    this.calculator = calculator;
    this.clock = clock;
  }

  private record Ordered(String eventType, Long previousCategoryId, Long newCategoryId) {}

  @Transactional
  public EntityLifecycleCorrectionResponse correct(
      StaffPrincipal principal, Long eventId, JsonNode body) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    CustomerInput.requireObject(body, KEYS);
    Instant effectiveAt = CustomerInput.requiredDateTime(body, "effectiveAt").toInstant();
    if (effectiveAt.isAfter(now)) {
      throw CustomerInput.invalid("effectiveAt", FieldErrorCode.OUT_OF_RANGE, "효력시각은 미래일 수 없습니다.");
    }
    String reason = CustomerInput.requiredText(body, "correctionReason", REASON_MAX, false);

    LifecycleEvent target =
        sourceRepository
            .findEvent(eventId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    EntityState state =
        sourceRepository
            .lockEntity(target)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (sourceRepository.isSuperseded(target.id())) {
      throw new BusinessException(ErrorCode.LIFECYCLE_ALREADY_SUPERSEDED);
    }
    List<LifecycleEvent> current = sourceRepository.validEvents(target);
    checkOrder(current, target, effectiveAt, state);

    Long newId =
        sourceRepository.insertCorrection(
            target, effectiveAt, reason, principal.membershipId(), now);
    calculator.recalculateIfStored(
        principal.officeId(), SettlementPeriods.of(target.effectiveAt()), now);
    calculator.recalculateIfStored(principal.officeId(), SettlementPeriods.of(effectiveAt), now);
    return new EntityLifecycleCorrectionResponse(
        newId, target.id(), TimeRules.toDisplayOffset(effectiveAt));
  }

  private static void checkOrder(
      List<LifecycleEvent> current, LifecycleEvent target, Instant effectiveAt, EntityState state) {
    record Timed(Instant at, long sequence, Ordered event) {}
    List<Timed> timeline = new ArrayList<>();
    for (LifecycleEvent event : current) {
      if (!event.id().equals(target.id())) {
        timeline.add(
            new Timed(
                event.effectiveAt(),
                event.id(),
                new Ordered(event.eventType(), event.previousCategoryId(), event.newCategoryId())));
      }
    }
    timeline.add(
        new Timed(
            effectiveAt,
            Long.MAX_VALUE,
            new Ordered(target.eventType(), target.previousCategoryId(), target.newCategoryId())));
    timeline.sort(Comparator.comparing(Timed::at).thenComparingLong(Timed::sequence));

    boolean created = false;
    String lastHolding = null;
    Long category = null;
    for (Timed timed : timeline) {
      Ordered event = timed.event();
      if (HOLDING.contains(event.eventType())) {
        boolean ok =
            switch (event.eventType()) {
              case "CREATED" -> !created;
              case "ARCHIVED" -> created && !"ARCHIVED".equals(lastHolding);
              default -> created && "ARCHIVED".equals(lastHolding);
            };
        if (!ok) {
          throw new BusinessException(ErrorCode.LIFECYCLE_ORDER_CONFLICT);
        }
        created = true;
        lastHolding = event.eventType();
      } else {
        if (!created
            || (category != null && !Objects.equals(category, event.previousCategoryId()))) {
          throw new BusinessException(ErrorCode.LIFECYCLE_ORDER_CONFLICT);
        }
        category = event.newCategoryId();
      }
    }
    boolean archivedAfter = "ARCHIVED".equals(lastHolding);
    boolean categoryChanged = category != null && !Objects.equals(category, state.categoryId());
    if (archivedAfter != state.archived() || categoryChanged) {
      throw new BusinessException(ErrorCode.LIFECYCLE_ORDER_CONFLICT);
    }
  }
}
