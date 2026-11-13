package com.sairo.be.domain.home.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class NotificationDeliveryScheduler {

  private final NotificationDeliveryService deliveryService;

  public NotificationDeliveryScheduler(NotificationDeliveryService deliveryService) {
    this.deliveryService = deliveryService;
  }

  @Scheduled(fixedDelayString = "PT1M")
  @SchedulerLock(
      name = "notification-delivery-retry",
      lockAtMostFor = "PT10M",
      lockAtLeastFor = "PT20S")
  public void run() {
    deliveryService.deliverDue();
  }
}
