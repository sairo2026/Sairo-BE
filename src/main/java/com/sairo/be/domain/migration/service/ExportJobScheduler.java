package com.sairo.be.domain.migration.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class ExportJobScheduler {

  private final ExportGenerationService generationService;

  public ExportJobScheduler(ExportGenerationService generationService) {
    this.generationService = generationService;
  }

  @Scheduled(fixedDelayString = "PT10S")
  @SchedulerLock(name = "export-job-generate", lockAtMostFor = "PT15M", lockAtLeastFor = "PT5S")
  public void generatePending() {
    generationService.generatePending();
  }

  @Scheduled(fixedDelayString = "PT10M")
  @SchedulerLock(name = "export-job-purge", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
  public void purgeExpired() {
    generationService.purgeExpired();
  }
}
