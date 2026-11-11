package com.sairo.be.domain.visit.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class VisitRequestExpiryScheduler {

  private final VisitRequestExpiryService expiryService;

  public VisitRequestExpiryScheduler(VisitRequestExpiryService expiryService) {
    this.expiryService = expiryService;
  }

  @Scheduled(fixedDelayString = "PT5M")
  public void run() {
    expiryService.expireOverdue();
  }
}
