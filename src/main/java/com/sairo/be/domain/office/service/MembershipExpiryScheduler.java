package com.sairo.be.domain.office.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class MembershipExpiryScheduler {

  private final MembershipExpiryService expiryService;
  private final OwnerTransferService transferService;

  public MembershipExpiryScheduler(
      MembershipExpiryService expiryService, OwnerTransferService transferService) {
    this.expiryService = expiryService;
    this.transferService = transferService;
  }

  @Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")
  @SchedulerLock(name = "membership-expiry", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
  public void run() {
    expiryService.expireOverdue();
    transferService.expireOverdue();
  }
}
