package com.sairo.be.domain.home.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 알림의 외부 채널 전달 상태(notification_delivery)를 JDBC로 다룬다. */
@Profile("!migrate")
@Repository
public class NotificationDeliveryRepository {

  public record DueRow(
      Long id,
      Long notificationId,
      String channel,
      int attemptCount,
      Long recipientUserId,
      String type,
      String message) {}

  private final JdbcClient jdbcClient;

  public NotificationDeliveryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public void enqueue(Long notificationId, String channel, Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO notification_delivery (notification_id, channel, status, next_attempt_at,"
                + " created_at, updated_at) VALUES (:notification, :channel, 'PENDING', :now,"
                + " :now, :now) ON CONFLICT (notification_id, channel) DO NOTHING")
        .param("notification", notificationId)
        .param("channel", channel)
        .param("now", Timestamp.from(now))
        .update();
  }

  public List<DueRow> claimDue(Instant now, int limit) {
    return jdbcClient
        .sql(
            "SELECT d.id, d.notification_id, d.channel, d.attempt_count, n.recipient_user_id,"
                + " n.notification_type, n.message FROM notification_delivery d JOIN notification"
                + " n ON n.id = d.notification_id WHERE d.status = 'PENDING' AND d.next_attempt_at"
                + " <= :now ORDER BY d.next_attempt_at, d.id LIMIT :limit FOR UPDATE OF d SKIP"
                + " LOCKED")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(
            (rs, i) ->
                new DueRow(
                    rs.getLong("id"),
                    rs.getLong("notification_id"),
                    rs.getString("channel"),
                    rs.getInt("attempt_count"),
                    rs.getLong("recipient_user_id"),
                    rs.getString("notification_type"),
                    rs.getString("message")))
        .list();
  }

  public Optional<String> recipientEmail(Long userId) {
    return jdbcClient
        .sql(
            "SELECT CASE WHEN email IS NOT NULL AND email_verified_at IS NOT NULL THEN email"
                + " ELSE kakao_reference_email END FROM app_user WHERE id = :id AND"
                + " account_status = 'ACTIVE'")
        .param("id", userId)
        .query(String.class)
        .optional();
  }

  public void markDelivered(Long id, int attemptCount, Instant now) {
    jdbcClient
        .sql(
            "UPDATE notification_delivery SET status = 'DELIVERED', attempt_count = :attempt,"
                + " last_attempted_at = :now, delivered_at = :now, next_attempt_at = NULL,"
                + " last_error_code = NULL, updated_at = :now WHERE id = :id")
        .param("id", id)
        .param("attempt", attemptCount)
        .param("now", Timestamp.from(now))
        .update();
  }

  public void markRetry(Long id, int attemptCount, Instant now, Instant next, String errorCode) {
    jdbcClient
        .sql(
            "UPDATE notification_delivery SET status = 'PENDING', attempt_count = :attempt,"
                + " last_attempted_at = :now, next_attempt_at = :next, last_error_code = :code,"
                + " updated_at = :now WHERE id = :id")
        .param("id", id)
        .param("attempt", attemptCount)
        .param("now", Timestamp.from(now))
        .param("next", Timestamp.from(next))
        .param("code", errorCode)
        .update();
  }

  public void markTerminal(Long id, String status, int attemptCount, Instant now, String code) {
    jdbcClient
        .sql(
            "UPDATE notification_delivery SET status = :status, attempt_count = :attempt,"
                + " last_attempted_at = :now, next_attempt_at = NULL, last_error_code = :code,"
                + " updated_at = :now WHERE id = :id")
        .param("id", id)
        .param("status", status)
        .param("attempt", attemptCount)
        .param("now", Timestamp.from(now))
        .param("code", code)
        .update();
  }

  public void recordAbandoned(Long deliveryId, Instant now, String errorCode) {
    jdbcClient
        .sql(
            "INSERT INTO system_job_run (job_name, idempotency_key, status, scheduled_at,"
                + " started_at, finished_at, error_code) VALUES ('notification-delivery-retry',"
                + " :key, 'FAILED', :now, :now, :now, :code) ON CONFLICT (idempotency_key) DO"
                + " NOTHING")
        .param("key", "notification-delivery:" + deliveryId)
        .param("now", Timestamp.from(now))
        .param("code", errorCode)
        .update();
  }
}
