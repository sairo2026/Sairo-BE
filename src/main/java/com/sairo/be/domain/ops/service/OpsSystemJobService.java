package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.service.BillingJobService;
import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.billing.service.SystemJobRunService.RerunOutcome;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.SystemJobItem;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.SystemJobListResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.SystemJobRerunResponse;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsSystemRepository;
import com.sairo.be.domain.ops.repository.OpsSystemRepository.JobFilter;
import com.sairo.be.domain.ops.repository.OpsSystemRepository.JobRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.PageQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시스템 작업 실행 이력과 재실행이다(OPS-09). 재실행은 작업 종류별 운영 역할만 할 수 있고, 실패한 실행만 원래 멱등키로 다시 실행한다. 작업은 실행 직전에 대상의
 * 현재 상태를 다시 검사하므로 성공한 결제를 다시 청구하지 않는다.
 */
@Profile("!migrate")
@Service
public class OpsSystemJobService {

  static final Set<String> STATUSES = Set.of("RUNNING", "SUCCESS", "FAILED");
  private static final Pattern JOB_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,99}$");

  /** 재실행할 수 있는 작업 종류와 그 역할이다. 목록에 없는 작업은 재실행 대상이 아니다. */
  enum JobType {
    CHARGE_RETRY(OperatorRole.BILLING),
    ANNOUNCEMENT_PUBLISH(OperatorRole.OPS),
    INTEGRATION_CHECK(OperatorRole.OPS),
    LEGAL_RETENTION(OperatorRole.PRIVACY),
    OTHER(null);

    private final OperatorRole rerunRole;

    JobType(OperatorRole rerunRole) {
      this.rerunRole = rerunRole;
    }

    static JobType of(String jobName) {
      if (BillingJobService.RERUNNABLE_CHARGE_JOBS.contains(jobName)) {
        return CHARGE_RETRY;
      }
      if (OpsContentService.ANNOUNCEMENT_PUBLISH_JOB.equals(jobName)) {
        return ANNOUNCEMENT_PUBLISH;
      }
      if (OpsIntegrationService.CHECK_JOB.equals(jobName)) {
        return INTEGRATION_CHECK;
      }
      if (LegalRetentionJobService.JOBS.contains(jobName)) {
        return LEGAL_RETENTION;
      }
      return OTHER;
    }

    List<String> rerunRoles() {
      return rerunRole == null ? List.of() : List.of(rerunRole.name());
    }
  }

  private final OpsSystemRepository repository;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final SystemJobRunService jobRunService;
  private final BillingJobService billingJobService;
  private final OpsContentService contentService;
  private final OpsIntegrationService integrationService;
  private final LegalRetentionJobService legalRetentionJobService;
  private final Clock clock;

  public OpsSystemJobService(
      OpsSystemRepository repository,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      SystemJobRunService jobRunService,
      BillingJobService billingJobService,
      OpsContentService contentService,
      OpsIntegrationService integrationService,
      LegalRetentionJobService legalRetentionJobService,
      Clock clock) {
    this.repository = repository;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.jobRunService = jobRunService;
    this.billingJobService = billingJobService;
    this.contentService = contentService;
    this.integrationService = integrationService;
    this.legalRetentionJobService = legalRetentionJobService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public SystemJobListResponse jobs(String jobName, String status, Long officeId, PageQuery page) {
    JobFilter filter =
        new JobFilter(
            jobName(jobName),
            OpsQueryParams.oneOf("status", status, STATUSES),
            OpsQueryParams.positiveId("officeId", officeId));
    List<SystemJobItem> items =
        repository.jobs(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(OpsSystemJobService::toItem)
            .toList();
    return new SystemJobListResponse(items, page.page(), page.size(), repository.countJobs(filter));
  }

  /**
   * 실패한 실행을 다시 실행한다. 역할 → 실행 ID → 상태 순으로 검사한다. 응답은 실행이 끝난 뒤의 실제 상태다. 실행 ID는 원래 실행 ID이며 시도 회수가 1
   * 오른다.
   */
  public SystemJobRerunResponse rerun(OperatorPrincipal principal, String jobName, long runId) {
    JobType type = JobType.of(jobName);
    if (type.rerunRole == null
        || !principal.hasRole(type.rerunRole)
        || !accounts.isActiveWithRole(principal.operatorId(), type.rerunRole)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    JobRow run =
        repository
            .job(runId)
            .filter(row -> row.jobName().equals(jobName))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!run.status().equals("FAILED")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    Runnable work =
        work(type, run, now).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    RerunOutcome outcome = jobRunService.rerunFailed(run.idempotencyKey(), work);
    if (outcome == RerunOutcome.NOT_STARTED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    auditLog.insert(
        principal.operatorId(),
        "SYSTEM_JOB_RERUN",
        "SYSTEM_JOB_RUN",
        runId,
        jobName + " " + outcome.name(),
        now);
    JobRow after = repository.job(runId).orElseThrow();
    return new SystemJobRerunResponse(
        after.id(),
        after.jobName(),
        after.status(),
        toDisplayOffset(after.startedAt()),
        after.attemptCount(),
        after.nextRunAt() == null ? null : toDisplayOffset(after.nextRunAt()));
  }

  private Optional<Runnable> work(JobType type, JobRow run, Instant now) {
    return switch (type) {
      case CHARGE_RETRY -> billingJobService.rerunWork(run.jobName(), run.idempotencyKey(), now);
      case ANNOUNCEMENT_PUBLISH ->
          contentService.announcementPublishRerunWork(run.idempotencyKey(), now);
      case INTEGRATION_CHECK -> integrationService.checkRerunWork(run.idempotencyKey());
      case LEGAL_RETENTION ->
          legalRetentionJobService.rerunWork(run.jobName(), run.idempotencyKey());
      case OTHER -> Optional.empty();
    };
  }

  private static String jobName(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip();
    if (!JOB_NAME.matcher(value).matches()) {
      throw OpsQueryParams.invalid("jobName", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 jobName 값입니다.");
    }
    return value;
  }

  private static SystemJobItem toItem(JobRow row) {
    JobType type = JobType.of(row.jobName());
    return new SystemJobItem(
        row.id(),
        row.jobName(),
        type.name(),
        type.rerunRoles(),
        row.officeId(),
        row.officeName(),
        row.status(),
        offset(row.scheduledAt()),
        offset(row.startedAt()),
        offset(row.finishedAt()),
        row.attemptCount(),
        row.errorCode(),
        offset(row.nextRunAt()),
        row.assignedOperatorId(),
        row.assignedOperatorName());
  }

  static java.time.OffsetDateTime offset(Instant instant) {
    return instant == null ? null : toDisplayOffset(instant);
  }
}
