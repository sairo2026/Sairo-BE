package com.sairo.be.domain.contract.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class LeasePeriodScheduler {

  private final LeasePeriodService leasePeriodService;

  public LeasePeriodScheduler(LeasePeriodService leasePeriodService) {
    this.leasePeriodService = leasePeriodService;
  }

  @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
  public void run() {
    leasePeriodService.refreshAll();
  }
}
