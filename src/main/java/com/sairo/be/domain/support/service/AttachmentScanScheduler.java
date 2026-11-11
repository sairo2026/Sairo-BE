package com.sairo.be.domain.support.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class AttachmentScanScheduler {

  private final AttachmentScanService scanService;

  public AttachmentScanScheduler(AttachmentScanService scanService) {
    this.scanService = scanService;
  }

  @Scheduled(fixedDelayString = "PT30S")
  public void recoverPending() {
    scanService.scanPending();
  }
}
