package com.sairo.be.domain.migration.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.migration.import-executor", havingValue = "sync")
public class SynchronousImportJobExecutor implements ImportJobExecutor {

  @Override
  public void submit(Runnable job) {
    Thread thread = Thread.ofVirtual().unstarted(job);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
