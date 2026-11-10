package com.sairo.be.domain.legal.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class DeletionQueueService {

  public static final String MEMBER_TARGET = "MEMBER";
  public static final Duration TARGET_DELAY = Duration.ofHours(24);

  private final JdbcClient jdbcClient;
  private final Clock clock;

  public DeletionQueueService(JdbcClient jdbcClient, Clock clock) {
    this.jdbcClient = jdbcClient;
    this.clock = clock;
  }

  @Transactional
  public void enqueueMemberDeletion(Long userId) {
    jdbcClient
        .sql(
            "INSERT INTO deletion_queue (target_type, target_id, reason, scheduled_at)"
                + " VALUES (:type, :id, :reason, :at)")
        .param("type", MEMBER_TARGET)
        .param("id", userId)
        .param("reason", "회원 탈퇴")
        .param("at", Timestamp.from(clock.instant().plus(TARGET_DELAY)))
        .update();
  }
}
