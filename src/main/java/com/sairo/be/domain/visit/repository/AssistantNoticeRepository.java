package com.sairo.be.domain.visit.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class AssistantNoticeRepository {

  public record NoticeRow(
      Long id,
      Long scheduleItemId,
      Long officeId,
      Long coordinationId,
      Long deputyMembershipId,
      String recipientType,
      String recipientName,
      String noticeMethod,
      Instant notifiedAt,
      boolean recordedAfterTheFact,
      String missingExceptionReason,
      String followupAction,
      Long confirmedByMembershipId,
      Instant confirmedAt,
      String supervisorAdditionalNotice,
      Instant supervisorAdditionalNoticedAt,
      Instant createdAt) {}

  public record NoticeInsert(
      Long scheduleItemId,
      Long deputyMembershipId,
      String recipientType,
      String recipientName,
      String noticeMethod,
      Instant notifiedAt,
      boolean recordedAfterTheFact,
      String missingExceptionReason,
      String followupAction,
      Instant createdAt) {}

  public record Filter(
      Long officeId,
      Instant from,
      Instant to,
      Long assistantMembershipId,
      Boolean confirmed,
      Boolean recordedAfterTheFact) {}

  private static final String SELECT =
      "SELECT n.id, n.schedule_item_id, si.office_id, si.coordination_id,"
          + " n.deputy_membership_id, n.recipient_type, n.recipient_name, n.notice_method,"
          + " n.notified_at, n.recorded_after_the_fact, n.missing_exception_reason,"
          + " n.followup_action, n.confirmed_by_membership_id, n.confirmed_at,"
          + " n.supervisor_additional_notice, n.supervisor_additional_noticed_at, n.created_at"
          + " FROM visit_assistant_notice_log n JOIN visit_schedule_item si ON si.id ="
          + " n.schedule_item_id";

  private final JdbcClient jdbcClient;

  public AssistantNoticeRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Long insert(NoticeInsert row) {
    return jdbcClient
        .sql(
            "INSERT INTO visit_assistant_notice_log (schedule_item_id, deputy_membership_id,"
                + " recipient_type, recipient_name, notice_method, notified_at,"
                + " recorded_after_the_fact, missing_exception_reason, followup_action,"
                + " created_at) VALUES (:item, :deputy, :recipientType, :recipientName, :method,"
                + " :notifiedAt, :after, :missing, :followup, :createdAt) RETURNING id")
        .param("item", row.scheduleItemId())
        .param("deputy", row.deputyMembershipId())
        .param("recipientType", row.recipientType())
        .param("recipientName", row.recipientName())
        .param("method", row.noticeMethod())
        .param("notifiedAt", timestamp(row.notifiedAt()))
        .param("after", row.recordedAfterTheFact())
        .param("missing", row.missingExceptionReason())
        .param("followup", row.followupAction())
        .param("createdAt", timestamp(row.createdAt()))
        .query(Long.class)
        .single();
  }

  public Optional<NoticeRow> find(Long noticeId) {
    return jdbcClient
        .sql(SELECT + " WHERE n.id = :id")
        .param("id", noticeId)
        .query((rs, rowNum) -> row(rs))
        .optional();
  }

  public int confirm(Long noticeId, Long membershipId, Instant at, String additionalNotice) {
    return jdbcClient
        .sql(
            "UPDATE visit_assistant_notice_log SET confirmed_by_membership_id = :member,"
                + " confirmed_at = :at, supervisor_additional_notice = :notice,"
                + " supervisor_additional_noticed_at = :noticedAt WHERE id = :id AND confirmed_at"
                + " IS NULL")
        .param("member", membershipId)
        .param("at", timestamp(at))
        .param("notice", additionalNotice)
        .param("noticedAt", additionalNotice == null ? null : timestamp(at))
        .param("id", noticeId)
        .update();
  }

  public List<NoticeRow> listByItem(Long itemId) {
    return jdbcClient
        .sql(SELECT + " WHERE n.schedule_item_id = :item ORDER BY n.created_at, n.id")
        .param("item", itemId)
        .query((rs, rowNum) -> row(rs))
        .list();
  }

  public List<NoticeRow> page(Filter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(filter, params);
    var spec =
        jdbcClient.sql(
            SELECT + where + " ORDER BY n.created_at DESC, n.id DESC LIMIT :limit OFFSET :offset");
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit).param("offset", offset).query((rs, rowNum) -> row(rs)).list();
  }

  public long count(Filter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(filter, params);
    var spec =
        jdbcClient.sql(
            "SELECT count(*) FROM visit_assistant_notice_log n JOIN visit_schedule_item si ON"
                + " si.id = n.schedule_item_id"
                + where);
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  private static String where(Filter filter, Map<String, Object> params) {
    List<String> conditions = new ArrayList<>();
    conditions.add("si.office_id = :office");
    params.put("office", filter.officeId());
    if (filter.from() != null) {
      conditions.add("n.created_at >= :from");
      params.put("from", timestamp(filter.from()));
    }
    if (filter.to() != null) {
      conditions.add("n.created_at < :to");
      params.put("to", timestamp(filter.to()));
    }
    if (filter.assistantMembershipId() != null) {
      conditions.add("n.deputy_membership_id = :assistant");
      params.put("assistant", filter.assistantMembershipId());
    }
    if (filter.confirmed() != null) {
      conditions.add(filter.confirmed() ? "n.confirmed_at IS NOT NULL" : "n.confirmed_at IS NULL");
    }
    if (filter.recordedAfterTheFact() != null) {
      conditions.add("n.recorded_after_the_fact = :after");
      params.put("after", filter.recordedAfterTheFact());
    }
    return " WHERE " + String.join(" AND ", conditions);
  }

  private static NoticeRow row(ResultSet rs) throws SQLException {
    long confirmer = rs.getLong("confirmed_by_membership_id");
    Long confirmedBy = rs.wasNull() ? null : confirmer;
    return new NoticeRow(
        rs.getLong("id"),
        rs.getLong("schedule_item_id"),
        rs.getLong("office_id"),
        rs.getLong("coordination_id"),
        rs.getLong("deputy_membership_id"),
        rs.getString("recipient_type"),
        rs.getString("recipient_name"),
        rs.getString("notice_method"),
        instant(rs.getTimestamp("notified_at")),
        rs.getBoolean("recorded_after_the_fact"),
        rs.getString("missing_exception_reason"),
        rs.getString("followup_action"),
        confirmedBy,
        instant(rs.getTimestamp("confirmed_at")),
        rs.getString("supervisor_additional_notice"),
        instant(rs.getTimestamp("supervisor_additional_noticed_at")),
        instant(rs.getTimestamp("created_at")));
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
