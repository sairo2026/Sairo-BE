package com.sairo.be.domain.home.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 알림함(notification 표)을 JDBC로 다룬다. 수신자 본인과 현재 사무소(또는 사무소 없음) 알림만 읽는다. */
@Profile("!migrate")
@Repository
public class NotificationRepository {

  public record NotificationRow(
      Long id,
      String type,
      String message,
      Instant readAt,
      String targetType,
      Long targetId,
      Instant createdAt) {}

  private static final String SCOPE =
      " WHERE n.recipient_user_id = :user AND (n.office_id IS NULL OR n.office_id = :office)";

  private final JdbcClient jdbcClient;

  public NotificationRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Long insert(
      Long officeId,
      Long recipientUserId,
      String type,
      String message,
      String targetType,
      Long targetId,
      Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO notification (office_id, recipient_user_id, notification_type, message,"
                + " target_type, target_id, created_at, updated_at) VALUES (:office, :user, :type,"
                + " :message, :targetType, :targetId, :now, :now) RETURNING id")
        .param("office", officeId)
        .param("user", recipientUserId)
        .param("type", type)
        .param("message", message)
        .param("targetType", targetType)
        .param("targetId", targetId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public long count(Long userId, Long officeId, boolean unreadOnly) {
    return jdbcClient
        .sql("SELECT count(*) FROM notification n" + SCOPE + unread(unreadOnly))
        .param("user", userId)
        .param("office", officeId)
        .query(Long.class)
        .single();
  }

  public List<NotificationRow> page(
      Long userId, Long officeId, boolean unreadOnly, int limit, long offset) {
    return jdbcClient
        .sql(
            "SELECT n.id, n.notification_type, n.message, n.read_at, n.target_type, n.target_id,"
                + " n.created_at FROM notification n"
                + SCOPE
                + unread(unreadOnly)
                + " ORDER BY n.created_at DESC, n.id DESC LIMIT :limit OFFSET :offset")
        .param("user", userId)
        .param("office", officeId)
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, rowNum) ->
                new NotificationRow(
                    rs.getLong("id"),
                    rs.getString("notification_type"),
                    rs.getString("message"),
                    TaskRepository.instant(rs, "read_at"),
                    rs.getString("target_type"),
                    rs.getObject("target_id", Long.class),
                    TaskRepository.instant(rs, "created_at")))
        .list();
  }

  /** 본인 알림이면 읽음 시각을 처음 한 번만 남기고 true를 돌려준다. 이미 읽은 알림도 true다. */
  public boolean markRead(Long notificationId, Long userId, Long officeId, Instant now) {
    int matched =
        jdbcClient
            .sql(
                "UPDATE notification n SET read_at = COALESCE(n.read_at, :now), updated_at ="
                    + " CASE WHEN n.read_at IS NULL THEN :now ELSE n.updated_at END"
                    + SCOPE
                    + " AND n.id = :id")
            .param("now", Timestamp.from(now))
            .param("user", userId)
            .param("office", officeId)
            .param("id", notificationId)
            .update();
    return matched > 0;
  }

  /** 수신 설정 키가 꺼져 있는지 본다. 행이 없으면 서비스 기본값(MARKETING만 꺼짐)을 따른다. */
  public boolean channelEnabled(Long userId, String channelKey) {
    return jdbcClient
        .sql(
            "SELECT COALESCE((SELECT enabled FROM app_user_notification_preference WHERE user_id"
                + " = :user AND channel_key = :key), :fallback)")
        .param("user", userId)
        .param("key", channelKey)
        .param("fallback", !"MARKETING".equals(channelKey))
        .query(Boolean.class)
        .single();
  }

  public List<Long> ownerUserIds(Long officeId) {
    return jdbcClient
        .sql(
            "SELECT user_id FROM office_membership WHERE office_id = :office AND role = 'OWNER'"
                + " AND status = 'APPROVED' ORDER BY id")
        .param("office", officeId)
        .query(Long.class)
        .list();
  }

  public Optional<Long> userOfMembership(Long membershipId) {
    return jdbcClient
        .sql("SELECT user_id FROM office_membership WHERE id = :id")
        .param("id", membershipId)
        .query(Long.class)
        .optional();
  }

  private static String unread(boolean unreadOnly) {
    return unreadOnly ? " AND n.read_at IS NULL" : "";
  }
}
