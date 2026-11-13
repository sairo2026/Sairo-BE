package com.sairo.be.domain.ops.service;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 예약 시각이 지난 예약 공지를 게시하는 배치다. */
@Profile("!migrate")
@Component
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class OpsContentScheduler {

  private final OpsContentService contentService;
  private final Clock clock;

  public OpsContentScheduler(OpsContentService contentService, Clock clock) {
    this.contentService = contentService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT1M")
  @SchedulerLock(
      name = "ops-announcement-publish",
      lockAtMostFor = "PT10M",
      lockAtLeastFor = "PT30S")
  public void run() {
    contentService.publishDueAnnouncements(clock.instant());
  }
}
