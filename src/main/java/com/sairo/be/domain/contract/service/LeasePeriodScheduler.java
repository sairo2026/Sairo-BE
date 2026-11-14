package com.sairo.be.domain.contract.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 계약 매일 배치(D90-01)다. D-90 후속업무 생성 뒤 임대기간 표시값 갱신을 순서대로 한다. */
@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class LeasePeriodScheduler {

  private final ContractFollowupService followupService;
  private final LeasePeriodService leasePeriodService;

  public LeasePeriodScheduler(
      ContractFollowupService followupService, LeasePeriodService leasePeriodService) {
    this.followupService = followupService;
    this.leasePeriodService = leasePeriodService;
  }

  @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
  @SchedulerLock(name = "lease-period-scan", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
  public void run() {
    followupService.createDue();
    leasePeriodService.refreshAll();
  }
}
