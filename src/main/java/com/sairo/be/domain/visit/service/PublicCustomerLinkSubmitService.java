package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.CustomerAvailableTimesResponse;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.entity.VisitCustomerTimeSlot;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerTimeSlotRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class PublicCustomerLinkSubmitService {

  static final int MAX_SLOTS = 50;

  private static final Set<String> BODY_KEYS = Set.of("timeSlots", "acknowledgedNoticeVersion");
  private static final Set<String> SLOT_KEYS = Set.of("startsAt", "endsAt");

  private record Slot(Instant startsAt, Instant endsAt) {}

  private record Submission(Set<Slot> slots, String acknowledgedNoticeVersion) {}

  private final PublicLinkResolver resolver;
  private final VisitCoordinationRepository coordinationRepository;
  private final VisitCustomerLinkRepository linkRepository;
  private final VisitCustomerTimeSlotRepository slotRepository;
  private final CoordinationStatusService statusService;
  private final CustomerLinkNoticeProvider noticeProvider;
  private final Clock clock;

  public PublicCustomerLinkSubmitService(
      PublicLinkResolver resolver,
      VisitCoordinationRepository coordinationRepository,
      VisitCustomerLinkRepository linkRepository,
      VisitCustomerTimeSlotRepository slotRepository,
      CoordinationStatusService statusService,
      CustomerLinkNoticeProvider noticeProvider,
      Clock clock) {
    this.resolver = resolver;
    this.coordinationRepository = coordinationRepository;
    this.linkRepository = linkRepository;
    this.slotRepository = slotRepository;
    this.statusService = statusService;
    this.noticeProvider = noticeProvider;
    this.clock = clock;
  }

  @Transactional
  public CustomerAvailableTimesResponse submit(
      String token, JsonNode body, String idempotencyKey, String clientIp) {
    VisitCustomerLinkRepository.LinkHead found = resolver.resolve(token, clientIp);
    Instant now = clock.instant();
    if (!now.isBefore(found.getExpiresAt())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_EXPIRED);
    }
    Submission submission = parse(body);

    VisitCoordination coordination =
        coordinationRepository
            .findForUpdateById(found.getCoordinationId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    VisitCustomerLink link =
        linkRepository
            .findForUpdate(found.getId())
            .filter(locked -> !locked.isRevoked())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (!coordination.getStatus().acceptsCustomerResponse()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (!noticeProvider.current().noticeVersion().equals(submission.acknowledgedNoticeVersion())) {
      throw new BusinessException(ErrorCode.NOTICE_VERSION_CHANGED);
    }
    requireWithinWindow(coordination, submission.slots());

    if (isRetry(idempotencyKey, link, submission.slots())) {
      return response(coordination, link);
    }
    int version = link.recordResponse(now);
    for (Slot slot : submission.slots()) {
      slotRepository.save(
          VisitCustomerTimeSlot.of(link.getId(), version, slot.startsAt(), slot.endsAt()));
    }
    slotRepository.flush();
    linkRepository.saveAndFlush(link);
    reflectStatus(coordination, submission.slots().isEmpty());
    coordination.touch(now);
    coordinationRepository.saveAndFlush(coordination);
    return response(coordination, link);
  }

  private void reflectStatus(VisitCoordination coordination, boolean cancelled) {
    CoordinationStatus current = coordination.getStatus();
    CoordinationStatus next = current;
    if (cancelled) {
      if (current == CoordinationStatus.COORDINATABLE
          || current == CoordinationStatus.FINAL_CONFIRMATION_REQUIRED) {
        next = CoordinationStatus.TIME_COLLECTING;
      }
    } else if (current == CoordinationStatus.TIME_COLLECTING
        || current == CoordinationStatus.FINAL_CONFIRMATION_REQUIRED) {
      next = CoordinationStatus.COORDINATABLE;
    }
    if (next != current) {
      statusService.move(coordination, next, StatusChangeSource.CUSTOMER, null, null);
    }
  }

  private boolean isRetry(String idempotencyKey, VisitCustomerLink link, Set<Slot> slots) {
    if (idempotencyKey == null || idempotencyKey.isBlank() || link.getResponseVersion() == 0) {
      return false;
    }
    Set<Slot> current = new LinkedHashSet<>();
    for (VisitCustomerTimeSlot slot :
        slotRepository.findByCustomerLinkIdAndResponseVersionOrderByStartsAtAscIdAsc(
            link.getId(), link.getResponseVersion())) {
      current.add(new Slot(slot.getStartsAt(), slot.getEndsAt()));
    }
    return current.equals(slots);
  }

  private void requireWithinWindow(VisitCoordination coordination, Set<Slot> slots) {
    Instant windowStart = TimeRules.startOfDisplayDay(coordination.getPeriodStartDate());
    Instant windowEnd = TimeRules.endOfDisplayDayExclusive(coordination.getPeriodEndDate());
    for (Slot slot : slots) {
      if (slot.startsAt().isBefore(windowStart) || slot.endsAt().isAfter(windowEnd)) {
        throw new BusinessException(ErrorCode.TIME_OUT_OF_RANGE);
      }
    }
  }

  private static CustomerAvailableTimesResponse response(
      VisitCoordination coordination, VisitCustomerLink link) {
    return new CustomerAvailableTimesResponse(
        coordination.getId(),
        link.getResponseVersion(),
        TimeRules.toDisplayOffset(link.getRespondedAt()));
  }

  private static Submission parse(JsonNode body) {
    CustomerInput.requireObject(body, BODY_KEYS);
    String acknowledged = CustomerInput.requiredText(body, "acknowledgedNoticeVersion", 50, false);
    if (CustomerInput.isNull(body, "timeSlots")) {
      throw CustomerInput.invalid("timeSlots", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    JsonNode node = body.get("timeSlots");
    if (!node.isArray()) {
      throw CustomerInput.invalid("timeSlots", FieldErrorCode.TYPE_MISMATCH, "배열이어야 합니다.");
    }
    if (node.size() > MAX_SLOTS) {
      throw CustomerInput.invalid(
          "timeSlots", FieldErrorCode.OUT_OF_RANGE, "한 번에 " + MAX_SLOTS + "개까지 제출할 수 있습니다.");
    }
    Set<Slot> slots = new LinkedHashSet<>();
    for (JsonNode element : node) {
      CustomerInput.requireObject(element, SLOT_KEYS);
      Instant startsAt = instant(element, "startsAt");
      Instant endsAt = instant(element, "endsAt");
      if (!endsAt.isAfter(startsAt)) {
        throw CustomerInput.invalid(
            "timeSlots", FieldErrorCode.INVALID_VALUE, "종료 시각은 시작 시각보다 늦어야 합니다.");
      }
      slots.add(new Slot(startsAt, endsAt));
    }
    return new Submission(slots, acknowledged);
  }

  private static Instant instant(JsonNode element, String key) {
    if (CustomerInput.isNull(element, key)) {
      throw CustomerInput.invalid("timeSlots", FieldErrorCode.REQUIRED, key + "는 필수 입력입니다.");
    }
    JsonNode value = element.get(key);
    if (!value.isString()) {
      throw CustomerInput.invalid(
          "timeSlots", FieldErrorCode.TYPE_MISMATCH, key + "는 일시 문자열이어야 합니다.");
    }
    try {
      return OffsetDateTime.parse(value.asString()).toInstant();
    } catch (DateTimeParseException e) {
      throw CustomerInput.invalid(
          "timeSlots", FieldErrorCode.INVALID_FORMAT, key + "는 ISO-8601 일시 형식이어야 합니다.");
    }
  }
}
