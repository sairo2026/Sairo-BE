package com.sairo.be.domain.migration.service;

import com.sairo.be.global.support.AfterCommit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ExportDispatcher {

  private final ImportJobExecutor jobExecutor;
  private final ExportGenerationService generationService;

  public ExportDispatcher(
      ImportJobExecutor jobExecutor, ExportGenerationService generationService) {
    this.jobExecutor = jobExecutor;
    this.generationService = generationService;
  }

  public void dispatch(long exportId) {
    AfterCommit.run(() -> jobExecutor.submit(() -> generationService.generate(exportId)));
  }
}
