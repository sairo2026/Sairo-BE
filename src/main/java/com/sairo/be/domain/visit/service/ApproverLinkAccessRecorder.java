package com.sairo.be.domain.visit.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Component
public class ApproverLinkAccessRecorder {

  private final JdbcClient jdbcClient;
  private final Clock clock;

  public ApproverLinkAccessRecorder(JdbcClient jdbcClient, Clock clock) {
    this.jdbcClient = jdbcClient;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(Long linkId) {
    jdbcClient
        .sql("UPDATE visit_approver_property_link SET last_accessed_at = :at WHERE id = :id")
        .param("at", java.sql.Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)))
        .param("id", linkId)
        .update();
  }
}
