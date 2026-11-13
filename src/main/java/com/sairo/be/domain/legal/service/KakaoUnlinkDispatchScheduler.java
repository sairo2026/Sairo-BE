package com.sairo.be.domain.legal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class KakaoUnlinkDispatchScheduler {

  private final KakaoUnlinkDispatchService dispatchService;

  public KakaoUnlinkDispatchScheduler(KakaoUnlinkDispatchService dispatchService) {
    this.dispatchService = dispatchService;
  }

  @Scheduled(fixedDelayString = "PT1M")
  @SchedulerLock(name = "kakao-unlink-dispatch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
  public void dispatch() {
    dispatchService.dispatchDue();
  }
}
