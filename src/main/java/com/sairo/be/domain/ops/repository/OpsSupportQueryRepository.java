package com.sairo.be.domain.ops.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 운영 콘솔 지원 문의·기업 도입 문의 목록과 표시 이름 조회다(OPS-05). */
@Profile("!migrate")
@Repository
public class OpsSupportQueryRepository {

  public record InquiryFilter(
      String category,
      String status,
      String priority,
      Long assignedOperatorId,
      boolean unassigned,
      boolean overdue,
      Long officeId,
      Instant now) {}

  public record InquiryRow(
      long id,
      Long officeId,
      String officeName,
      long requesterUserId,
      String requesterName,
      boolean requesterWithdrawn,
      String category,
      String status,
      String priority,
      Long assignedOperatorId,
      String assignedOperatorName,
      Instant responseDueAt,
      Instant answeredAt,
      Instant closedAt,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  public record LeadFilter(String status, Long assignedOperatorId, boolean unassigned) {}

  public record LeadRow(
      long id,
      String companyName,
      String status,
      Long assignedOperatorId,
      String assignedOperatorName,
      String contactName,
      int branchCount,
      int expectedSeats,
      boolean migrationNeeded,
      Long quoteAmount,
      Instant quoteExpiresAt,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  public record UserName(String name, boolean withdrawn) {}

  private static final String INQUIRY_FROM =
      " FROM support_inquiry i JOIN app_user u ON u.id = i.requester_user_id LEFT JOIN office o"
          + " ON o.id = i.office_id LEFT JOIN operator_account a ON a.id = i.assigned_operator_id";

  private final JdbcClient jdbcClient;

  public OpsSupportQueryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<InquiryRow> inquiries(InquiryFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = inquiryWhere(filter, params);
    var spec =
        jdbcClient.sql(
            "SELECT i.id, i.office_id, o.name AS office_name, i.requester_user_id, u.name AS"
                + " user_name, u.account_status, i.category, i.status, i.priority,"
                + " i.assigned_operator_id, a.name AS operator_name, i.response_due_at,"
                + " i.answered_at, i.closed_at, i.created_at, i.updated_at, i.version"
                + INQUIRY_FROM
                + where
                + " ORDER BY CASE WHEN i.status = 'CLOSED' THEN 1 ELSE 0 END, i.response_due_at,"
                + " i.id LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(OpsSupportQueryRepository::mapInquiry)
        .list();
  }

  public long countInquiries(InquiryFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = inquiryWhere(filter, params);
    var spec = jdbcClient.sql("SELECT count(*)" + INQUIRY_FROM + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  private static String inquiryWhere(InquiryFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.category() != null) {
      where.append(" AND i.category = :category");
      params.put("category", filter.category());
    }
    if (filter.status() != null) {
      where.append(" AND i.status = :status");
      params.put("status", filter.status());
    }
    if (filter.priority() != null) {
      where.append(" AND i.priority = :priority");
      params.put("priority", filter.priority());
    }
    if (filter.assignedOperatorId() != null) {
      where.append(" AND i.assigned_operator_id = :assignee");
      params.put("assignee", filter.assignedOperatorId());
    }
    if (filter.unassigned()) {
      where.append(" AND i.assigned_operator_id IS NULL");
    }
    if (filter.overdue()) {
      where.append(" AND i.answered_at IS NULL AND i.response_due_at < :now");
      params.put("now", Timestamp.from(filter.now()));
    }
    if (filter.officeId() != null) {
      where.append(" AND i.office_id = :officeId");
      params.put("officeId", filter.officeId());
    }
    return where.toString();
  }

  public Optional<String> officeName(long officeId) {
    return jdbcClient
        .sql("SELECT name FROM office WHERE id = :id")
        .param("id", officeId)
        .query(String.class)
        .optional();
  }

  public Map<Long, UserName> userNames(Collection<Long> userIds) {
    Map<Long, UserName> names = new HashMap<>();
    if (userIds.isEmpty()) {
      return names;
    }
    jdbcClient
        .sql("SELECT id, name, account_status FROM app_user WHERE id IN (:ids)")
        .param("ids", userIds)
        .query(
            (rs, i) -> {
              names.put(
                  rs.getLong("id"),
                  new UserName(
                      rs.getString("name"), "WITHDRAWN".equals(rs.getString("account_status"))));
              return null;
            })
        .list();
    return names;
  }

  /** 개인정보 권리 요청과 연결된 문의인지 본다. 연결 문의는 법정 기한을 답변 목표로 쓴다. */
  public boolean linkedToPrivacyRequest(long inquiryId) {
    return jdbcClient
            .sql("SELECT count(*) FROM privacy_request WHERE support_inquiry_id = :id")
            .param("id", inquiryId)
            .query(Long.class)
            .single()
        > 0;
  }

  public List<LeadRow> leads(LeadFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = leadWhere(filter, params);
    var spec =
        jdbcClient.sql(
            "SELECT l.id, l.company_name, l.status, l.assigned_operator_id, a.name AS"
                + " operator_name, l.contact_name, l.branch_count, l.expected_seats,"
                + " l.migration_needed, l.quote_amount, l.quote_expires_at, l.created_at,"
                + " l.updated_at, l.version FROM enterprise_lead l LEFT JOIN operator_account a"
                + " ON a.id = l.assigned_operator_id"
                + where
                + " ORDER BY l.created_at DESC, l.id DESC LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(OpsSupportQueryRepository::mapLead)
        .list();
  }

  public long countLeads(LeadFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = leadWhere(filter, params);
    var spec = jdbcClient.sql("SELECT count(*) FROM enterprise_lead l" + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  private static String leadWhere(LeadFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.status() != null) {
      where.append(" AND l.status = :status");
      params.put("status", filter.status());
    }
    if (filter.assignedOperatorId() != null) {
      where.append(" AND l.assigned_operator_id = :assignee");
      params.put("assignee", filter.assignedOperatorId());
    }
    if (filter.unassigned()) {
      where.append(" AND l.assigned_operator_id IS NULL");
    }
    return where.toString();
  }

  private static InquiryRow mapInquiry(ResultSet rs, int row) throws SQLException {
    return new InquiryRow(
        rs.getLong("id"),
        rs.getObject("office_id", Long.class),
        rs.getString("office_name"),
        rs.getLong("requester_user_id"),
        rs.getString("user_name"),
        "WITHDRAWN".equals(rs.getString("account_status")),
        rs.getString("category"),
        rs.getString("status"),
        rs.getString("priority"),
        rs.getObject("assigned_operator_id", Long.class),
        rs.getString("operator_name"),
        instant(rs.getTimestamp("response_due_at")),
        instant(rs.getTimestamp("answered_at")),
        instant(rs.getTimestamp("closed_at")),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")),
        rs.getLong("version"));
  }

  private static LeadRow mapLead(ResultSet rs, int row) throws SQLException {
    return new LeadRow(
        rs.getLong("id"),
        rs.getString("company_name"),
        rs.getString("status"),
        rs.getObject("assigned_operator_id", Long.class),
        rs.getString("operator_name"),
        rs.getString("contact_name"),
        rs.getInt("branch_count"),
        rs.getInt("expected_seats"),
        rs.getBoolean("migration_needed"),
        rs.getObject("quote_amount", Long.class),
        instant(rs.getTimestamp("quote_expires_at")),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")),
        rs.getLong("version"));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
