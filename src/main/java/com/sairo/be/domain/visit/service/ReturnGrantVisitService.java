package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.billing.service.ReturnGrantService;
import com.sairo.be.domain.billing.service.ReturnGrantService.VisitActor;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.ReturnVisitCancelResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.ReturnVisitResultResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitAccessRevocationResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitCoordinationCancelResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitScheduleItemResultResponse;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 반환권한(VISIT-09, BILL-09)으로 종료된 사무소의 기존 방문을 정리한다. 매 요청 반환권한의 만료·회수와 대상이 반환권한 사무소에 속하는지 다시 검사하고 활성
 * 소속은 요구하지 않는다. 기록자는 반환권한 사용자의 보존된 소속이며 새 조율·일정 구성·확정은 하지 않는다. 실제 처리는 구성원 경로와 같은 서비스를 쓴다.
 */
@Profile("!migrate")
@Service
public class ReturnGrantVisitService {

  private static final Set<String> REVOCATION_KEYS = Set.of("approvalId", "revokedAt", "reason");
  private static final Set<String> RESULT_KEYS =
      Set.of("result", "actualVisitedAt", "effectiveAt", "correctionReason");

  private final ReturnGrantService returnGrantService;
  private final CoordinationDisruptionService disruptionService;
  private final ScheduleResultService resultService;
  private final VisitCoordinationRepository coordinationRepository;
  private final Clock clock;

  public ReturnGrantVisitService(
      ReturnGrantService returnGrantService,
      CoordinationDisruptionService disruptionService,
      ScheduleResultService resultService,
      VisitCoordinationRepository coordinationRepository,
      Clock clock) {
    this.returnGrantService = returnGrantService;
    this.disruptionService = disruptionService;
    this.resultService = resultService;
    this.coordinationRepository = coordinationRepository;
    this.clock = clock;
  }

  @Transactional
  public ReturnVisitCancelResponse cancel(
      GrantPrincipal principal,
      Long grantId,
      Long coordinationId,
      long expectedVersion,
      JsonNode body) {
    StaffPrincipal actor = actor(principal, grantId);
    VisitCoordinationCancelResponse cancelled =
        disruptionService.cancel(actor, coordinationId, expectedVersion, body);
    return new ReturnVisitCancelResponse(
        cancelled.coordinationId(), cancelled.officeId(), cancelled.status(), cancelled.version());
  }

  @Transactional
  public VisitAccessRevocationResponse revokeAccess(
      GrantPrincipal principal,
      Long grantId,
      Long coordinationId,
      long expectedVersion,
      JsonNode body) {
    CustomerInput.requireObject(body, REVOCATION_KEYS);
    CustomerInput.requiredText(body, "reason", 500, false);
    StaffPrincipal actor = actor(principal, grantId);
    ObjectNode staffBody = ((ObjectNode) body).deepCopy();
    staffBody.set("revocationReason", staffBody.remove("reason"));
    return disruptionService.revokeAccess(actor, coordinationId, expectedVersion, staffBody);
  }

  @Transactional
  public ReturnVisitResultResponse recordResult(
      GrantPrincipal principal, Long grantId, Long itemId, long expectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, RESULT_KEYS);
    OffsetDateTime effectiveAt = CustomerInput.requiredDateTime(body, "effectiveAt");
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (effectiveAt.toInstant().isAfter(now)) {
      throw CustomerInput.invalid("effectiveAt", FieldErrorCode.OUT_OF_RANGE, "미래 시각은 입력할 수 없습니다.");
    }
    StaffPrincipal actor = actor(principal, grantId);
    ObjectNode staffBody = ((ObjectNode) body).deepCopy();
    staffBody.remove("effectiveAt");
    Instant effective = effectiveAt.toInstant().truncatedTo(ChronoUnit.MICROS);
    VisitScheduleItemResultResponse recorded =
        resultService.record(actor, itemId, expectedVersion, staffBody, effective);
    VisitCoordination coordination =
        coordinationRepository
            .findById(recorded.coordinationId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return new ReturnVisitResultResponse(
        recorded.itemId(),
        recorded.coordinationId(),
        coordination.getOfficeId(),
        recorded.result(),
        recorded.actualVisitedAt(),
        TimeRules.toDisplayOffset(effective),
        recorded.resultRecordedAt(),
        recorded.coordinationStatus(),
        coordination.getVersion());
  }

  /** 반환권한 사용자를 그 사무소의 보존된 소속으로 바꾼다. 담당 범위 제한이 없는 대표 권한으로 처리하되 사무소는 반환권한의 사무소로 고정한다. */
  private StaffPrincipal actor(GrantPrincipal principal, Long grantId) {
    VisitActor actor = returnGrantService.requireVisitActor(principal, grantId);
    return new StaffPrincipal(
        actor.userId(), actor.membershipId(), actor.officeId(), null, StaffRole.OWNER);
  }
}
