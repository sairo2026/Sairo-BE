package com.sairo.be.domain.ops.service;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 시행을 앞둔 약관 새 버전의 14일 전 안내를 매시 20분에 확인하는 배치다. */
@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class TermsEffectiveNoticeScheduler {

  private final TermsEffectiveNoticeService service;
  private final Clock clock;

  public TermsEffectiveNoticeScheduler(TermsEffectiveNoticeService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @Scheduled(cron = "0 20 * * * *", zone = "Asia/Seoul")
  @SchedulerLock(name = "terms-effective-notice", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
  public void run() {
    service.sendDue(clock.instant());
  }
}
