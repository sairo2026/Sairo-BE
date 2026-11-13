package com.sairo.be.domain.ops.service;

import static com.sairo.be.domain.ops.service.OpsSystemJobService.offset;

import com.sairo.be.domain.billing.service.SystemJobFailure;
import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.ops.dto.request.OpsSystemRequests.IntegrationUpdateRequest;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IntegrationCheckResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IntegrationItem;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IntegrationListResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IntegrationUpdateResponse;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsSystemRepository;
import com.sairo.be.domain.ops.repository.OpsSystemRepository.IntegrationChange;
import com.sairo.be.domain.ops.repository.OpsSystemRepository.IntegrationRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.IntegrationProbe;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.global.support.PageQuery;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연동 설정 조회·변경과 연결 검사다(OPS-09). 비밀값은 다루지 않고 비밀관리 저장소의 참조 ID와 마스킹된 연결 정보만 보인다. 운영 환경에서 활성 상태가 되거나
 * 공급자·처리지역을 바꾸는 변경은 계약 체결, 동의, 이번 요청의 계약·고지 점검, 회사 정보 출시 준비가 끝나야 하고 변경한 운영자를 승인자로 기록한다.
 */
@Profile("!migrate")
@Service
public class OpsIntegrationService {

  /** 연결 검사의 system_job_run 작업 이름이다. */
  public static final String CHECK_JOB = "ops-integration-check";

  static final Set<String> ENVIRONMENTS = Set.of("TEST", "PRODUCTION");
  static final Set<String> CONTRACT_STATUSES = Set.of("PENDING", "SIGNED", "EXPIRED", "TERMINATED");
  static final Set<String> CONSENT_STATUSES =
      Set.of("NOT_REQUIRED", "PENDING", "GRANTED", "WITHDRAWN");
  private static final Set<String> CONSENT_READY = Set.of("NOT_REQUIRED", "GRANTED");
  private static final String CHECK_KEY = "integration-check:";
  private static final Pattern FUNCTION_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

  private final OpsSystemRepository repository;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final OpsContentService contentService;
  private final SystemJobRunService jobRunService;
  private final ObjectProvider<IntegrationProbe> probe;
  private final OperatorStepUpState stepUp;
  private final Clock clock;

  public OpsIntegrationService(
      OpsSystemRepository repository,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      OpsContentService contentService,
      SystemJobRunService jobRunService,
      ObjectProvider<IntegrationProbe> probe,
      OperatorStepUpState stepUp,
      Clock clock) {
    this.repository = repository;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.contentService = contentService;
    this.jobRunService = jobRunService;
    this.probe = probe;
    this.stepUp = stepUp;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public IntegrationListResponse integrations(
      String environment, String functionCode, PageQuery page) {
    String env = OpsQueryParams.oneOf("environment", environment, ENVIRONMENTS);
    String function = functionCode(functionCode);
    List<IntegrationItem> items =
        repository
            .integrations(env, function, page.size(), (long) page.page() * page.size())
            .stream()
            .map(OpsIntegrationService::toItem)
            .toList();
    return new IntegrationListResponse(
        items, page.page(), page.size(), repository.countIntegrations(env, function));
  }

  @Transactional
  public IntegrationUpdateResponse update(
      long operatorId, long integrationId, IntegrationUpdateRequest body, HttpSession session) {
    String contractInput =
        OpsQueryParams.oneOf("contractStatus", body.contractStatus(), CONTRACT_STATUSES);
    String consentInput =
        OpsQueryParams.oneOf("consentStatus", body.consentStatus(), CONSENT_STATUSES);
    if (body.provider() != null && body.provider().isBlank()) {
      throw OpsQueryParams.invalid("provider", FieldErrorCode.REQUIRED, "공급자는 비울 수 없습니다.");
    }
    if (body.retentionPolicy() != null && body.retentionPolicy().isBlank()) {
      throw OpsQueryParams.invalid("retentionPolicy", FieldErrorCode.REQUIRED, "보유기간은 비울 수 없습니다.");
    }
    IntegrationRow row =
        repository
            .lockedIntegration(integrationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (body.enabled() && !row.enabled()) {
      stepUp.require(session);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String provider = body.provider() == null ? row.provider() : body.provider().strip();
    String region =
        body.processingRegion() == null
            ? row.processingRegion()
            : body.processingRegion().isBlank() ? null : body.processingRegion().strip();
    String retention =
        body.retentionPolicy() == null ? row.retentionPolicy() : body.retentionPolicy().strip();
    String contract = contractInput == null ? row.contractStatus() : contractInput;
    String consent = consentInput == null ? row.consentStatus() : consentInput;
    boolean providerChanged = !provider.equals(row.provider());
    boolean regionChanged = !Objects.equals(region, row.processingRegion());
    boolean reviewedNow = Boolean.TRUE.equals(body.contractNoticeReviewed());
    if (providerChanged
        && repository.otherIntegrationExists(
            integrationId, row.environment(), provider, row.functionCode())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant reviewedAt =
        reviewedNow
            ? now
            : providerChanged || regionChanged ? null : row.contractNoticeReviewedAt();
    boolean enabled = body.enabled();
    Long approvedBy = row.approvedByOperatorId();
    boolean production = row.environment().equals("PRODUCTION");
    if (production && (enabled || providerChanged || regionChanged)) {
      List<String> missing = new ArrayList<>();
      if (!contract.equals("SIGNED")) {
        missing.add("CONTRACT_SIGNED");
      }
      if (!CONSENT_READY.contains(consent)) {
        missing.add("CONSENT");
      }
      if (reviewedAt == null) {
        missing.add("CONTRACT_NOTICE_REVIEW");
      }
      contentService.missingLaunchItems().forEach(item -> missing.add("COMPANY_INFO_" + item));
      if (!missing.isEmpty()) {
        throw BusinessException.withDetails(
            ErrorCode.INTEGRATION_PREREQUISITE_INCOMPLETE, Map.of("missingItems", missing));
      }
      boolean approving =
          (enabled && !row.enabled()) || providerChanged || regionChanged || approvedBy == null;
      if (approving) {
        if (!accounts.isActiveWithRole(operatorId, OperatorRole.OPS)) {
          throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        approvedBy = operatorId;
      }
    }
    repository.updateIntegration(
        integrationId,
        new IntegrationChange(
            enabled, provider, region, retention, contract, consent, reviewedAt, approvedBy));
    auditLog.insert(
        operatorId,
        "INTEGRATION_UPDATED",
        "INTEGRATION_CONFIG",
        integrationId,
        changes(row, enabled, provider, region, contract, consent, reviewedNow),
        now);
    IntegrationRow after = repository.integration(integrationId).orElseThrow();
    return new IntegrationUpdateResponse(
        after.id(),
        after.environment(),
        after.provider(),
        after.functionCode(),
        after.enabled(),
        after.maskedConnection(),
        after.processingRegion(),
        after.retentionPolicy(),
        after.contractStatus(),
        after.consentStatus(),
        offset(after.contractNoticeReviewedAt()),
        after.lastCheckResult(),
        offset(after.lastSuccessAt()));
  }

  /**
   * 연결 검사를 실행하고 결과를 기록한다. 검사 한 번은 system_job_run 실행 하나이고 실패하면 시스템 작업에서 다시 실행할 수 있다. 공급자 호출이 실패해도
   * 응답은 성공이며 결과가 FAILED다.
   */
  public IntegrationCheckResponse check(long operatorId, long integrationId) {
    if (repository.integration(integrationId).isEmpty()) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    jobRunService.runOnce(
        CHECK_JOB,
        null,
        CHECK_KEY + integrationId + ":" + UUID.randomUUID(),
        null,
        checkWork(integrationId));
    IntegrationRow after = repository.integration(integrationId).orElseThrow();
    String result = after.lastCheckResult() == null ? "FAILED" : after.lastCheckResult();
    auditLog.insert(
        operatorId, "INTEGRATION_CHECKED", "INTEGRATION_CONFIG", integrationId, result, now());
    return new IntegrationCheckResponse(after.id(), result, offset(after.lastSuccessAt()));
  }

  /** 연결 검사 재실행이다. 키에서 연동을 찾아 지금 다시 검사한다. */
  Optional<Runnable> checkRerunWork(String idempotencyKey) {
    String[] parts = idempotencyKey.split(":");
    if (parts.length != 3 || !idempotencyKey.startsWith(CHECK_KEY)) {
      return Optional.empty();
    }
    try {
      return Optional.of(checkWork(Long.parseLong(parts[1])));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private Runnable checkWork(long integrationId) {
    return () -> {
      IntegrationRow row =
          repository
              .integration(integrationId)
              .orElseThrow(() -> new SystemJobFailure("INTEGRATION_NOT_FOUND"));
      IntegrationProbe port = probe.getIfAvailable();
      IntegrationProbe.Result result =
          port == null
              ? IntegrationProbe.Result.failed("PROBE_UNAVAILABLE")
              : port.probe(
                  new IntegrationProbe.Target(
                      row.id(),
                      row.environment(),
                      row.provider(),
                      row.functionCode(),
                      row.secretReferenceId()));
      repository.recordCheck(integrationId, result.success() ? "SUCCESS" : "FAILED", now());
      if (!result.success()) {
        throw new SystemJobFailure(
            result.errorCode() == null ? "INTEGRATION_CHECK_FAILED" : result.errorCode());
      }
    };
  }

  private static String changes(
      IntegrationRow row,
      boolean enabled,
      String provider,
      String region,
      String contract,
      String consent,
      boolean reviewedNow) {
    List<String> parts = new ArrayList<>();
    if (enabled != row.enabled()) {
      parts.add("활성 " + row.enabled() + "→" + enabled);
    }
    if (!provider.equals(row.provider())) {
      parts.add("공급자 " + row.provider() + "→" + provider);
    }
    if (!Objects.equals(region, row.processingRegion())) {
      parts.add("처리지역 " + row.processingRegion() + "→" + region);
    }
    if (!contract.equals(row.contractStatus())) {
      parts.add("계약 " + row.contractStatus() + "→" + contract);
    }
    if (!consent.equals(row.consentStatus())) {
      parts.add("동의 " + row.consentStatus() + "→" + consent);
    }
    if (reviewedNow) {
      parts.add("계약·고지 점검");
    }
    return parts.isEmpty() ? "보유기간 등 변경" : String.join(", ", parts);
  }

  private static String functionCode(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip();
    if (!FUNCTION_CODE.matcher(value).matches()) {
      throw OpsQueryParams.invalid(
          "functionCode", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 functionCode 값입니다.");
    }
    return value;
  }

  private static IntegrationItem toItem(IntegrationRow row) {
    return new IntegrationItem(
        row.id(),
        row.environment(),
        row.provider(),
        row.functionCode(),
        row.enabled(),
        row.secretReferenceId(),
        row.maskedConnection(),
        row.processingRegion(),
        row.retentionPolicy(),
        row.contractStatus(),
        row.consentStatus(),
        offset(row.contractNoticeReviewedAt()),
        row.lastCheckResult(),
        offset(row.lastSuccessAt()),
        row.assignedOperatorId(),
        row.approvedByOperatorId());
  }

  private Instant now() {
    return clock.instant();
  }
}
