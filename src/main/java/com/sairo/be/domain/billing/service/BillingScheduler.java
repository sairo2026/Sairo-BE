package com.sairo.be.domain.billing.service;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class BillingScheduler {

  private final BillingJobService jobService;
  private final Clock clock;

  public BillingScheduler(BillingJobService jobService, Clock clock) {
    this.jobService = jobService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT5M")
  public void run() {
    jobService.runAll(clock.instant());
  }
}
