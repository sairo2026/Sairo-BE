package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.legal.service.LegalRetentionBatchService;
import com.sairo.be.domain.legal.service.LegalRetentionBatchService.AnonymizeResult;
import com.sairo.be.domain.legal.service.LegalRetentionBatchService.SweepResult;
import com.sairo.be.global.support.TimeRules;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 보관·파기 법률 배치의 실행 기록이다(기술설계 3.18, OPS-09). 작업마다 `{작업 이름}:{KST 날짜}` 키로 하루 한 번 돌고, 실패하면 시스템 작업 화면에
 * 드러나며 개인정보담당이 같은 키로 재실행한다. 작업 본체는 멱등이라 재실행이 결과를 바꾸지 않는다.
 */
@Slf4j
@Profile("!migrate")
@Service
public class LegalRetentionJobService {

  public static final String SWEEP_JOB = "retention-sweep";
  public static final String ANONYMIZE_JOB = "approver-anonymize";
  public static final String LINK_LOG_PURGE_JOB = "legal-link-log-purge";
  public static final Set<String> JOBS = Set.of(SWEEP_JOB, ANONYMIZE_JOB, LINK_LOG_PURGE_JOB);

  private static final Map<String, Duration> SCHEDULED_AT =
      Map.of(
          SWEEP_JOB, Duration.ofMinutes(3 * 60 + 10),
          ANONYMIZE_JOB, Duration.ofMinutes(3 * 60 + 20),
          LINK_LOG_PURGE_JOB, Duration.ofMinutes(3 * 60 + 30));

  private final LegalRetentionBatchService batchService;
  private final SystemJobRunService jobRunService;

  public LegalRetentionJobService(
      LegalRetentionBatchService batchService, SystemJobRunService jobRunService) {
    this.batchService = batchService;
    this.jobRunService = jobRunService;
  }

  /** 정해진 날짜의 작업을 한 번 실행한다. 이미 성공했거나 실행 중이면 건너뛰고 false다. */
  public boolean runScheduled(String jobName, Instant now) {
    LocalDate day = TimeRules.displayDate(now);
    return jobRunService.runOnce(
        jobName,
        null,
        jobName + ":" + day,
        TimeRules.startOfDisplayDay(day).plus(SCHEDULED_AT.get(jobName)),
        work(jobName));
  }

  /** 실패한 실행의 재실행 작업이다. 키가 이 작업의 날짜 키 형식이 아니면 비어 있다. */
  public Optional<Runnable> rerunWork(String jobName, String idempotencyKey) {
    if (!JOBS.contains(jobName) || !idempotencyKey.startsWith(jobName + ":")) {
      return Optional.empty();
    }
    return Optional.of(work(jobName));
  }

  private Runnable work(String jobName) {
    return switch (jobName) {
      case SWEEP_JOB ->
          () -> {
            SweepResult result = batchService.sweep();
            log.info(
                "보관 기한 정리: 새 파기 큐 {}건, 건수 갱신 {}건, 대상 {}행, 연락처 비움 {}건",
                result.queued(),
                result.refreshed(),
                result.dueRows(),
                result.contactsWiped());
          };
      case ANONYMIZE_JOB ->
          () -> {
            AnonymizeResult result = batchService.anonymizeApproverLinks();
            log.info(
                "승인자 익명화: 링크 {}건, 인증 사건 IP 변환값 {}건",
                result.linksAnonymized(),
                result.authEventsCleared());
          };
      case LINK_LOG_PURGE_JOB ->
          () -> log.info("비회원 링크 접속 로그 삭제: {}건", batchService.purgeLinkAccessLogs());
      default -> throw new IllegalArgumentException("알 수 없는 법률 배치다: " + jobName);
    };
  }
}
