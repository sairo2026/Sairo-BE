package com.sairo.be.domain.ops.service;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 수신 설정 통지 발송(10분마다)과 2년 재확인 대상 생성(매일 02:30 KST)을 돌리는 배치다. */
@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class ConsentNoticeScheduler {

  private final ConsentNoticeJobService service;
  private final Clock clock;

  public ConsentNoticeScheduler(ConsentNoticeJobService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
  @SchedulerLock(name = "consent-notice-send", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
  public void send() {
    service.send();
  }

  @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Seoul")
  @SchedulerLock(name = "marketing-reconfirm-scan", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
  public void scanReconfirm() {
    service.scanReconfirm(clock.instant());
  }
}
