package com.sairo.be.domain.billing.service;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class BillingScheduler {

  private final PriceScheduleActivationService activationService;
  private final BillingJobService jobService;
  private final PlanNoticeJobService planNoticeJobService;
  private final Clock clock;

  public BillingScheduler(
      PriceScheduleActivationService activationService,
      BillingJobService jobService,
      PlanNoticeJobService planNoticeJobService,
      Clock clock) {
    this.activationService = activationService;
    this.jobService = jobService;
    this.planNoticeJobService = planNoticeJobService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT5M")
  @SchedulerLock(name = "billing-cycle", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
  public void run() {
    activationService.activateDue(clock.instant());
    jobService.runAll(clock.instant());
    planNoticeJobService.runAll(clock.instant());
  }
}
