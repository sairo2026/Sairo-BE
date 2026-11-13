package com.sairo.be.domain.billing.service;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 09:00 KST에 세금 증빙 발급 대기 기록을 만드는 배치다. */
@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class TaxDocumentScheduler {

  private final TaxDocumentPendingScanService service;
  private final Clock clock;

  public TaxDocumentScheduler(TaxDocumentPendingScanService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
  @SchedulerLock(
      name = "tax-document-pending-scan",
      lockAtMostFor = "PT1H",
      lockAtLeastFor = "PT5M")
  public void scan() {
    service.runScheduled(clock.instant());
  }
}
