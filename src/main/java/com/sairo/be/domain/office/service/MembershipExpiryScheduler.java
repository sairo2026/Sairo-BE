package com.sairo.be.domain.office.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class MembershipExpiryScheduler {

  private final MembershipExpiryService expiryService;

  public MembershipExpiryScheduler(MembershipExpiryService expiryService) {
    this.expiryService = expiryService;
  }

  @Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")
  public void run() {
    expiryService.expireOverdue();
  }
}
