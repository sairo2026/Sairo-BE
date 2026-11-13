package com.sairo.be.domain.ops.service;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 KST 새벽 4시에 시행 중이거나 예정인 약관 원문의 해시를 대조하는 배치다(OPS-18). */
@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class TermsHashCheckScheduler {

  private final OpsLegalOpsService service;
  private final Clock clock;

  public TermsHashCheckScheduler(OpsLegalOpsService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
  @SchedulerLock(name = "terms-hash-check", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
  public void run() {
    service.runScheduledTermsHashCheck(clock.instant());
  }
}
