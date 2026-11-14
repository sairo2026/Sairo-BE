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
  public static final String RETENTION_REASON = "보관 기한 만료";

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

  /**
   * 보관 기한이 지난 데이터군을 파기 큐에 올린다. 같은 대상의 처리되지 않은 큐 행이 있으면 새로 만들지 않고 건수만 갱신한다. 실행 예정 시각은 지금이며 실행과 검증은
   * 운영자가 한다. 새 행을 만들었으면 true다.
   */
  @Transactional
  public boolean enqueueRetention(String targetType, Long officeId, long targetId, long count) {
    int updated =
        jdbcClient
            .sql(
                "UPDATE deletion_queue SET target_count = :count WHERE target_type = :type AND"
                    + " target_id = :id AND executed_at IS NULL AND cancelled_at IS NULL AND result"
                    + " IS NULL")
            .param("count", count)
            .param("type", targetType)
            .param("id", targetId)
            .update();
    if (updated > 0) {
      return false;
    }
    jdbcClient
        .sql(
            "INSERT INTO deletion_queue (target_type, target_id, office_id, reason, scheduled_at,"
                + " target_count) VALUES (:type, :id, :office, :reason, :at, :count)")
        .param("type", targetType)
        .param("id", targetId)
        .param("office", officeId)
        .param("reason", RETENTION_REASON)
        .param("at", Timestamp.from(clock.instant()))
        .param("count", count)
        .update();
    return true;
  }
}
