package com.sairo.be.domain.ops.service;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 KST 새벽 보관 기한 정리(03:10), 승인자 익명화(03:20), 비회원 링크 접속 로그 삭제(03:30)를 돌리는 배치다. */
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
  public void sweep() {
    service.runScheduled(LegalRetentionJobService.SWEEP_JOB, clock.instant());
  }

  @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Seoul")
  public void anonymize() {
    service.runScheduled(LegalRetentionJobService.ANONYMIZE_JOB, clock.instant());
  }

  @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
  public void purgeLinkLogs() {
    service.runScheduled(LegalRetentionJobService.LINK_LOG_PURGE_JOB, clock.instant());
  }
}
