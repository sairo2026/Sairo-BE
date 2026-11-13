package com.sairo.be.domain.ops.service;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 KST 새벽 보관 기한 정리(03:10), 승인자 익명화(03:20), 비회원 링크 접속 로그 삭제(03:30), 승인자 연락처 암호화(03:40)를 돌리는 배치다.
 */
@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class LegalRetentionScheduler {

  private final LegalRetentionJobService service;
  private final Clock clock;

  public LegalRetentionScheduler(LegalRetentionJobService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Seoul")
  @SchedulerLock(name = "legal-retention-sweep", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
  public void sweep() {
    service.runScheduled(LegalRetentionJobService.SWEEP_JOB, clock.instant());
  }

  @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Seoul")
  @SchedulerLock(name = "legal-approver-anonymize", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
  public void anonymize() {
    service.runScheduled(LegalRetentionJobService.ANONYMIZE_JOB, clock.instant());
  }

  @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
  @SchedulerLock(name = "legal-link-log-purge", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
  public void purgeLinkLogs() {
    service.runScheduled(LegalRetentionJobService.LINK_LOG_PURGE_JOB, clock.instant());
  }

  @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Seoul")
  @SchedulerLock(
      name = "legal-approver-phone-encrypt",
      lockAtMostFor = "PT2H",
      lockAtLeastFor = "PT5M")
  public void encryptApproverPhones() {
    service.runScheduled(LegalRetentionJobService.APPROVER_PHONE_ENCRYPT_JOB, clock.instant());
  }
}
