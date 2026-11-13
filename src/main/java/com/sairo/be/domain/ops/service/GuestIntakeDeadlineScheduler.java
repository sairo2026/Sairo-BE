package com.sairo.be.domain.ops.service;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 09:10 KST에 비회원 권리 요청 접수의 기한을 점검하는 배치다. */
@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class GuestIntakeDeadlineScheduler {

  private final GuestIntakeDeadlineScanService service;
  private final Clock clock;

  public GuestIntakeDeadlineScheduler(GuestIntakeDeadlineScanService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @Scheduled(cron = "0 10 9 * * *", zone = "Asia/Seoul")
  @SchedulerLock(
      name = "guest-intake-deadline-scan",
      lockAtMostFor = "PT1H",
      lockAtLeastFor = "PT5M")
  public void scan() {
    service.runScheduled(clock.instant());
  }
}
