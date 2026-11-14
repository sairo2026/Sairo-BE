package com.sairo.be.domain.ops.service;

import static com.sairo.be.domain.ops.service.OpsSystemJobService.offset;

import com.sairo.be.domain.ops.dto.request.OpsSystemRequests.IncidentCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsSystemRequests.IncidentUpdateRequest;
import com.sairo.be.domain.ops.dto.request.OpsSystemRequests.PrivacyIncidentCreateRequest;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IncidentCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IncidentItem;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IncidentListResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.PrivacyIncidentCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.PrivacyIncidentItem;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.PrivacyIncidentListResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.PrivacyIncidentUpdateResponse;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsSystemRepository;
import com.sairo.be.domain.ops.repository.OpsSystemRepository.IncidentChange;
import com.sairo.be.domain.ops.repository.OpsSystemRepository.IncidentRow;
import com.sairo.be.domain.ops.repository.OpsSystemRepository.NewIncident;
import com.sairo.be.domain.ops.repository.OpsSystemRepository.NewPrivacyIncident;
import com.sairo.be.domain.ops.repository.OpsSystemRepository.PrivacyIncidentRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.support.PageQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장애 기록(감지 → 확인 → 대응 → 복구 → 종료를 한 단계씩)과 장애에 연결한 개인정보 사고(사건당 1건, 개인정보담당만)다(OPS-09). 모든 쓰기에 감사 로그를
 * 남기고 개인정보 사고의 범위·증빙 내용은 감사 상세에 옮기지 않는다.
 */
@Profile("!migrate")
@Service
public class OpsIncidentService {

  static final List<String> STATUS_ORDER =
      List.of("DETECTED", "CONFIRMED", "RESPONDING", "RECOVERED", "CLOSED");
  private static final Pattern SERVICE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

  private final OpsSystemRepository repository;
  private final OpsAuditLogRepository auditLog;
  private final Clock clock;

  public OpsIncidentService(
      OpsSystemRepository repository, OpsAuditLogRepository auditLog, Clock clock) {
    this.repository = repository;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  // 장애

  @Transactional(readOnly = true)
  public IncidentListResponse incidents(String status, PageQuery page) {
    String filter = OpsQueryParams.oneOf("status", status, new java.util.HashSet<>(STATUS_ORDER));
    List<IncidentItem> items =
        repository.incidents(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(OpsIncidentService::toItem)
            .toList();
    return new IncidentListResponse(
        items, page.page(), page.size(), repository.countIncidents(filter));
  }

  @Transactional
  public IncidentCreateResponse createIncident(long operatorId, IncidentCreateRequest body) {
    Instant now = now();
    List<String> services = serviceCodes(body.affectedServices());
    List<Long> offices =
        body.affectedOfficeIds() == null
            ? List.of()
            : List.copyOf(new LinkedHashSet<>(body.affectedOfficeIds()));
    if (repository.countExistingOffices(offices) != offices.size()) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    Instant startedAt = notFuture("startedAt", body.startedAt(), now);
    requireActiveOperator(body.assignedOperatorId());
    long id =
        repository.insertIncident(
            new NewIncident(
                body.title().strip(),
                String.join(",", services),
                offices.isEmpty() ? null : join(offices),
                now,
                startedAt,
                text(body.rootCause()),
                body.assignedOperatorId()));
    auditLog.insert(operatorId, "INCIDENT_CREATED", "INCIDENT", id, body.title().strip(), now);
    return new IncidentCreateResponse(
        id,
        body.title().strip(),
        "DETECTED",
        offset(now),
        offset(startedAt),
        body.assignedOperatorId());
  }

  /** 상태는 한 단계씩만 나아가고 같은 상태면 다른 필드만 고친다. 종료 시각은 종료로 바뀔 때의 서버 시각이다. */
  @Transactional
  public IncidentItem updateIncident(long operatorId, long incidentId, IncidentUpdateRequest body) {
    int target = STATUS_ORDER.indexOf(body.status());
    if (target < 0) {
      throw OpsQueryParams.invalid("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 status 값입니다.");
    }
    IncidentRow row =
        repository
            .lockedIncident(incidentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    int current = STATUS_ORDER.indexOf(row.status());
    if (target != current && target != current + 1) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = now();
    int recovered = STATUS_ORDER.indexOf("RECOVERED");
    Instant recoveredAt = row.recoveredAt();
    if (body.recoveredAt() != null) {
      if (target < recovered) {
        throw OpsQueryParams.invalid(
            "recoveredAt", FieldErrorCode.NOT_ALLOWED, "복구 시각은 복구 이후 단계에서만 기록합니다.");
      }
      recoveredAt = notFuture("recoveredAt", body.recoveredAt(), now);
      if (row.startedAt() != null && recoveredAt.isBefore(row.startedAt())) {
        throw OpsQueryParams.invalid(
            "recoveredAt", FieldErrorCode.OUT_OF_RANGE, "복구 시각은 장애 시작 시각 이후여야 합니다.");
      }
    } else if (target >= recovered && recoveredAt == null) {
      recoveredAt = now;
    }
    Instant closedAt = row.closedAt();
    if (body.status().equals("CLOSED") && closedAt == null) {
      closedAt = now;
    }
    Long assigned = row.assignedOperatorId();
    if (body.assignedOperatorId() != null) {
      requireActiveOperator(body.assignedOperatorId());
      assigned = body.assignedOperatorId();
    }
    repository.updateIncident(
        incidentId,
        new IncidentChange(
            body.status(),
            recoveredAt,
            closedAt,
            assigned,
            keep(body.rootCause(), row.rootCause()),
            keep(body.responseActions(), row.responseActions()),
            keep(body.customerNotice(), row.customerNotice()),
            keep(body.preventionTasks(), row.preventionTasks()),
            keep(body.summary(), row.summary())));
    auditLog.insert(
        operatorId,
        "INCIDENT_UPDATED",
        "INCIDENT",
        incidentId,
        row.status() + "→" + body.status(),
        now);
    return toItem(repository.lockedIncident(incidentId).orElseThrow());
  }

  // 개인정보 사고

  @Transactional(readOnly = true)
  public PrivacyIncidentListResponse privacyIncidents(PageQuery page) {
    List<PrivacyIncidentItem> items =
        repository.privacyIncidents(page.size(), (long) page.page() * page.size()).stream()
            .map(OpsIncidentService::toItem)
            .toList();
    return new PrivacyIncidentListResponse(
        items, page.page(), page.size(), repository.countPrivacyIncidents());
  }

  /** 장애 사건 한 건에 하나만 연결한다. 담당자는 요청한 개인정보담당이다. */
  @Transactional
  public PrivacyIncidentCreateResponse createPrivacyIncident(
      long operatorId, PrivacyIncidentCreateRequest body) {
    if (!repository.incidentExists(body.incidentLogId())) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    Instant now = now();
    Instant reportDue = micros(body.reportDueAt());
    Instant noticeDue = micros(body.noticeDueAt());
    long id =
        repository
            .insertPrivacyIncident(
                new NewPrivacyIncident(
                    body.incidentLogId(),
                    body.affectedScope().strip(),
                    body.reportRequirement().strip(),
                    body.noticeRequirement().strip(),
                    reportDue,
                    noticeDue,
                    operatorId,
                    now))
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    auditLog.insert(
        operatorId,
        "PRIVACY_INCIDENT_CREATED",
        "PRIVACY_INCIDENT",
        id,
        "장애 " + body.incidentLogId(),
        now);
    return new PrivacyIncidentCreateResponse(
        id, body.incidentLogId(), offset(reportDue), offset(noticeDue), operatorId, offset(now));
  }

  @Transactional
  public PrivacyIncidentUpdateResponse updatePrivacyIncident(
      long operatorId, long privacyIncidentId, String responseEvidence) {
    String evidence = responseEvidence.strip();
    if (repository.updatePrivacyEvidence(privacyIncidentId, evidence) == 0) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    auditLog.insert(
        operatorId,
        "PRIVACY_INCIDENT_EVIDENCE_UPDATED",
        "PRIVACY_INCIDENT",
        privacyIncidentId,
        "증빙 위치 갱신",
        now());
    return new PrivacyIncidentUpdateResponse(privacyIncidentId, evidence);
  }

  // 공통

  private void requireActiveOperator(Long operatorId) {
    if (operatorId != null && !repository.activeOperator(operatorId)) {
      throw OpsQueryParams.invalid(
          "assignedOperatorId", FieldErrorCode.NOT_ALLOWED, "활성 운영자만 담당자로 지정할 수 있습니다.");
    }
  }

  private static List<String> serviceCodes(List<String> raw) {
    LinkedHashSet<String> codes = new LinkedHashSet<>();
    for (String value : raw) {
      String code = value == null ? "" : value.strip();
      if (!SERVICE_CODE.matcher(code).matches()) {
        throw OpsQueryParams.invalid(
            "affectedServices", FieldErrorCode.NOT_ALLOWED, "영향 서비스 코드는 대문자·숫자·밑줄만 씁니다.");
      }
      codes.add(code);
    }
    return List.copyOf(codes);
  }

  private static Instant notFuture(String field, OffsetDateTime value, Instant now) {
    Instant instant = value.toInstant().truncatedTo(ChronoUnit.MICROS);
    if (instant.isAfter(now)) {
      throw OpsQueryParams.invalid(field, FieldErrorCode.OUT_OF_RANGE, field + "는 현재 이전이어야 합니다.");
    }
    return instant;
  }

  private static Instant micros(OffsetDateTime value) {
    return value == null ? null : value.toInstant().truncatedTo(ChronoUnit.MICROS);
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String keep(String value, String current) {
    return value == null ? current : text(value);
  }

  private static String join(List<Long> ids) {
    return String.join(",", ids.stream().map(String::valueOf).toList());
  }

  private static List<String> split(String value) {
    return value == null || value.isBlank() ? List.of() : Arrays.asList(value.split(","));
  }

  private static IncidentItem toItem(IncidentRow row) {
    return new IncidentItem(
        row.id(),
        row.title(),
        split(row.affectedServices()),
        split(row.affectedOffices()).stream().map(Long::valueOf).toList(),
        row.status(),
        offset(row.detectedAt()),
        offset(row.startedAt()),
        offset(row.recoveredAt()),
        offset(row.closedAt()),
        row.assignedOperatorId(),
        row.rootCause(),
        row.responseActions(),
        row.customerNotice(),
        row.preventionTasks(),
        row.summary());
  }

  private static PrivacyIncidentItem toItem(PrivacyIncidentRow row) {
    return new PrivacyIncidentItem(
        row.id(),
        row.incidentLogId(),
        row.affectedScope(),
        row.reportRequirement(),
        row.noticeRequirement(),
        offset(row.reportDueAt()),
        offset(row.noticeDueAt()),
        row.responseEvidence(),
        row.assignedOperatorId(),
        offset(row.createdAt()));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
