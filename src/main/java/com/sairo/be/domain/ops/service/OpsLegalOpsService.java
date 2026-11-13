package com.sairo.be.domain.ops.service;

import static com.sairo.be.domain.ops.service.OpsSystemJobService.offset;

import com.sairo.be.domain.billing.service.SystemJobFailure;
import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.ops.dto.request.OpsLegalOpsRequests.RetentionRuleUpdateRequest;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.KakaoUnlinkEventItem;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.KakaoUnlinkEventListResponse;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.KakaoUnlinkEventRetryResponse;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.RetentionRuleListResponse;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.RetentionRuleResponse;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.TermsHashCheckItem;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.TermsHashCheckListResponse;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.TermsHashCheckRunItem;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.TermsHashCheckRunResponse;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsLegalOpsRepository;
import com.sairo.be.domain.ops.repository.OpsLegalOpsRepository.RuleChange;
import com.sairo.be.domain.ops.repository.OpsLegalOpsRepository.RuleRow;
import com.sairo.be.domain.ops.repository.OpsLegalOpsRepository.TermsRow;
import com.sairo.be.domain.ops.repository.OpsLegalOpsRepository.UnlinkRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.TermsDocumentReader;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.TimeRules;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 약관 원문 해시 점검(OPS-18), 보관 기간 규칙과 카카오 연결 끊기 사건(OPS-19)이다. 해시 점검은 시행 중이거나 예정인 모든 약관 버전의 원문을 다시 읽어 등록
 * 해시와 대조한다. 보관 기간 규칙 변경은 이후 새로 생기거나 종결되는 행부터 적용하고 이미 정한 삭제 예정일은 다시 계산하지 않는다.
 */
@Slf4j
@Profile("!migrate")
@Service
public class OpsLegalOpsService {

  /** 매일 해시 점검 배치의 system_job_run 작업 이름이다. */
  public static final String TERMS_HASH_JOB = "ops-terms-hash-check";

  static final Set<String> RULE_STATUSES =
      Set.of("ASSUMPTION", "LAWYER_CONFIRMED", "ADVISOR_CONFIRMED");
  static final Set<String> DIRECTIONS = Set.of("OUTBOUND_CALL", "INBOUND_WEBHOOK");
  static final Set<String> UNLINK_STATUSES =
      Set.of(
          "PENDING",
          "SUCCEEDED",
          "FAILED_RETRYING",
          "ABANDONED",
          "APPLIED",
          "NO_MATCH",
          "DUPLICATE_IGNORED");
  private static final Set<String> RETRYABLE = Set.of("PENDING", "FAILED_RETRYING");
  private static final int HASH_PREFIX = 8;

  private final OpsLegalOpsRepository repository;
  private final OpsAuditLogRepository auditLog;
  private final SystemJobRunService jobRunService;
  private final ObjectProvider<TermsDocumentReader> reader;
  private final OperatorStepUpState stepUp;
  private final Clock clock;

  public OpsLegalOpsService(
      OpsLegalOpsRepository repository,
      OpsAuditLogRepository auditLog,
      SystemJobRunService jobRunService,
      ObjectProvider<TermsDocumentReader> reader,
      OperatorStepUpState stepUp,
      Clock clock) {
    this.repository = repository;
    this.auditLog = auditLog;
    this.jobRunService = jobRunService;
    this.reader = reader;
    this.stepUp = stepUp;
    this.clock = clock;
  }

  // 약관 해시 점검

  @Transactional(readOnly = true)
  public TermsHashCheckListResponse termsHashChecks() {
    Instant now = clock.instant();
    return new TermsHashCheckListResponse(
        repository.liveTerms(now).stream()
            .map(
                row ->
                    new TermsHashCheckItem(
                        row.termsCode(),
                        row.version(),
                        row.effectiveAt().isAfter(now) ? "SCHEDULED" : "ACTIVE",
                        offset(row.effectiveAt()),
                        offset(row.lastCheckedAt()),
                        row.lastResult()))
            .toList());
  }

  /** 운영자의 즉시 실행이다. 불일치나 읽기 오류가 있으면 감사 기록에 해당 약관을 남긴다. */
  public TermsHashCheckRunResponse runTermsHashCheck(long operatorId) {
    TermsHashCheckRunResponse result = checkAll();
    String detail = "불일치 " + result.mismatchCount() + ", 오류 " + result.errorCount();
    List<String> failed =
        result.items().stream()
            .filter(item -> !item.result().equals("MATCH"))
            .map(item -> item.termsCode() + ":" + item.version() + " " + item.result())
            .toList();
    if (!failed.isEmpty()) {
      detail = detail + " (" + String.join(", ", failed) + ")";
    }
    auditLog.insert(
        operatorId, "TERMS_HASH_CHECK_RUN", "TERMS_CATALOG", null, detail, clock.instant());
    return result;
  }

  /**
   * 매일 KST 4시 배치다. 날짜마다 `terms-hash-check:{KST 날짜}` 키로 한 번만 돌고, 불일치나 읽기 오류가 있으면 실행을 실패로 남겨 시스템 작업
   * 화면에 드러낸다.
   */
  public void runScheduledTermsHashCheck(Instant now) {
    LocalDate day = TimeRules.displayDate(now);
    jobRunService.runOnce(
        TERMS_HASH_JOB,
        null,
        "terms-hash-check:" + day,
        TimeRules.startOfDisplayDay(day).plus(4, ChronoUnit.HOURS),
        () -> {
          TermsHashCheckRunResponse result = checkAll();
          if (result.mismatchCount() + result.errorCount() > 0) {
            log.warn(
                "게시된 약관 원문이 등록 시점과 다릅니다. mismatch={} error={}",
                result.mismatchCount(),
                result.errorCount());
            throw new SystemJobFailure("TERMS_HASH_MISMATCH");
          }
        });
  }

  private TermsHashCheckRunResponse checkAll() {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    TermsDocumentReader port = reader.getIfAvailable();
    List<TermsHashCheckRunItem> items = new ArrayList<>();
    int mismatch = 0;
    int error = 0;
    for (TermsRow row : repository.liveTerms(now)) {
      Optional<String> body = Optional.empty();
      if (port != null) {
        try {
          body = port.read(row.termsCode(), row.version(), row.documentUrl());
        } catch (RuntimeException e) {
          log.warn("약관 원문을 읽지 못했습니다. terms={}:{}", row.termsCode(), row.version(), e);
        }
      }
      String actual = body.map(OpsLegalOpsService::sha256).orElse(null);
      String result;
      if (actual == null) {
        result = "ERROR";
        error++;
      } else if (actual.equalsIgnoreCase(row.contentHash())) {
        result = "MATCH";
      } else {
        result = "MISMATCH";
        mismatch++;
      }
      repository.recordHashCheck(row.termsCode(), row.version(), result, now);
      items.add(
          new TermsHashCheckRunItem(
              row.termsCode(),
              row.version(),
              result,
              prefix(row.contentHash()),
              actual == null ? null : prefix(actual)));
    }
    return new TermsHashCheckRunResponse(offset(now), mismatch, error, items);
  }

  static String sha256(String body) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String prefix(String hash) {
    return hash.length() <= HASH_PREFIX ? hash : hash.substring(0, HASH_PREFIX);
  }

  // 보관 기간 규칙

  @Transactional(readOnly = true)
  public RetentionRuleListResponse retentionRules() {
    return new RetentionRuleListResponse(
        repository.rules().stream().map(OpsLegalOpsService::toResponse).toList());
  }

  @Transactional
  public RetentionRuleResponse updateRetentionRule(
      long operatorId,
      String ruleCode,
      String ifMatch,
      RetentionRuleUpdateRequest body,
      HttpSession session) {
    long expected = IfMatchVersion.parse(ifMatch);
    if (!RULE_STATUSES.contains(body.status())) {
      throw OpsQueryParams.invalid("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 status 값입니다.");
    }
    RuleRow row =
        repository
            .lockedRule(ruleCode)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    stepUp.require(session);
    if (row.version() != expected) {
      throw BusinessException.versionConflict(row.version());
    }
    Instant now = clock.instant();
    RuleRow updated =
        repository
            .updateRule(
                ruleCode,
                expected,
                new RuleChange(
                    body.retentionDays(),
                    body.basisNote().strip(),
                    body.status(),
                    TimeRules.displayDate(now),
                    operatorId,
                    now))
            .orElseThrow(() -> BusinessException.versionConflict(row.version()));
    auditLog.insert(
        operatorId,
        "RETENTION_RULE_CHANGED",
        "RETENTION_RULE",
        row.id(),
        ruleCode
            + " "
            + row.retentionDays()
            + "일→"
            + updated.retentionDays()
            + "일, "
            + row.status()
            + "→"
            + updated.status()
            + ", 사유: "
            + body.changeReason().strip(),
        now);
    return toResponse(updated);
  }

  private static RetentionRuleResponse toResponse(RuleRow row) {
    return new RetentionRuleResponse(
        row.ruleCode(),
        row.dataGroupLabel(),
        row.retentionDays(),
        row.anchorEvent(),
        row.basisNote(),
        row.status(),
        row.effectiveFrom(),
        row.version());
  }

  // 카카오 연결 끊기 사건

  @Transactional(readOnly = true)
  public KakaoUnlinkEventListResponse kakaoUnlinkEvents(
      String direction, String status, PageQuery page) {
    String directionFilter = OpsQueryParams.oneOf("direction", direction, DIRECTIONS);
    String statusFilter = OpsQueryParams.oneOf("status", status, UNLINK_STATUSES);
    List<KakaoUnlinkEventItem> items =
        repository
            .unlinkEvents(
                directionFilter, statusFilter, page.size(), (long) page.page() * page.size())
            .stream()
            .map(OpsLegalOpsService::toItem)
            .toList();
    return new KakaoUnlinkEventListResponse(
        items,
        page.page(),
        page.size(),
        repository.countUnlinkEvents(directionFilter, statusFilter));
  }

  /** 대기·재시도 상태의 호출 작업을 지금 다시 시도하도록 돌린다. 실제 호출은 1분 주기 배치가 한다. */
  @Transactional
  public KakaoUnlinkEventRetryResponse retryKakaoUnlink(long operatorId, long eventId) {
    UnlinkRow row =
        repository
            .lockedUnlinkEvent(eventId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!row.direction().equals("OUTBOUND_CALL") || !RETRYABLE.contains(row.status())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    repository.scheduleRetry(eventId, now);
    auditLog.insert(
        operatorId,
        "KAKAO_UNLINK_RETRY_REQUESTED",
        "KAKAO_UNLINK_EVENT",
        eventId,
        row.status() + "→PENDING",
        now);
    return new KakaoUnlinkEventRetryResponse(eventId, "PENDING", row.attemptCount(), offset(now));
  }

  private static KakaoUnlinkEventItem toItem(UnlinkRow row) {
    return new KakaoUnlinkEventItem(
        row.id(),
        row.direction(),
        row.status(),
        row.attemptCount(),
        offset(row.nextAttemptAt()),
        row.lastErrorCode(),
        row.referrerType(),
        row.userId(),
        offset(row.occurredAt()),
        offset(row.completedAt()));
  }
}
