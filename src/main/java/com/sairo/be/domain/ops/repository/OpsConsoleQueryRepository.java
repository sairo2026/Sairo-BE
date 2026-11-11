package com.sairo.be.domain.ops.repository;

import static com.sairo.be.domain.ops.repository.OpsApprovalRequestRepository.instant;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 운영 홈·고객사·회원 조회용 읽기 쿼리다. 다른 도메인의 표를 JDBC로만 읽는다. */
@Profile("!migrate")
@Repository
public class OpsConsoleQueryRepository {

  public record QueueCounts(
      long pendingReview,
      long unassignedInquiry,
      long paymentPending,
      long refundFailed,
      long migrationReviewPending,
      long privacyDeadlineApproaching,
      long deletionFailed,
      long activeIncident) {}

  public record SubscriptionCounts(long trial, long active, long pastDue, long ended) {}

  public record OfficeFilter(
      String keyword, String registrationStatus, String subscriptionStatus) {}

  public record OfficeRow(
      long id,
      String name,
      String representativeName,
      String businessRegistrationNumber,
      String realEstateLicenseNumber,
      String phone,
      String registrationAuthority,
      String address,
      String registrationStatus,
      String subscriptionStatus,
      long approvedMembershipCount,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  public record HistoryRow(
      String fromStatus, String toStatus, String changedByType, String reason, Instant createdAt) {}

  public record MemberRow(long membershipId, String displayName, String role, String status) {}

  public record SubscriptionRow(
      String status,
      String plan,
      Instant trialEndsAt,
      Instant currentPeriodEnd,
      Instant pastDueSince) {}

  public record GrantRow(long id, Instant grantedAt, Instant expiresAt, Instant endedAt) {}

  public record MemberFilter(
      String keyword, String accountStatus, String registrationStatus, String subscriptionStatus) {}

  public record UserListRow(
      long userId,
      String displayName,
      String accountStatus,
      String officeName,
      Instant createdAt) {}

  public record UserRow(
      long id,
      String name,
      String accountStatus,
      Instant withdrawnAt,
      boolean hasEmail,
      boolean hasKakao,
      Instant loginLockedUntil) {}

  public record CurrentMembershipRow(long officeId, String officeName, String role) {}

  public record SuspensionRow(
      Instant suspendedAt, String scope, String reason, Instant reviewAt, String operatorName) {}

  public record LoginRow(Instant createdAt, String userAgent) {}

  private static final String REGISTRATION_STATUS = "COALESCE(reg.status, 'APPROVED')";

  private static final String OFFICE_SELECT =
      "SELECT o.id, o.name, o.representative_name, o.business_registration_number,"
          + " o.real_estate_license_number, o.representative_phone, o.registration_authority,"
          + " o.address, "
          + REGISTRATION_STATUS
          + " AS registration_status, s.status AS subscription_status, (SELECT count(*) FROM"
          + " office_membership m WHERE m.office_id = o.id AND m.status = 'APPROVED') AS"
          + " approved_count, o.created_at, o.updated_at, o.version FROM office o"
          + " LEFT JOIN office_registration reg ON reg.resulting_office_id = o.id"
          + " LEFT JOIN subscription s ON s.office_id = o.id";

  private final JdbcClient jdbcClient;

  public OpsConsoleQueryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  // ---- 운영 홈 ----

  public QueueCounts queueCounts(Instant now, Instant privacyDeadline) {
    return jdbcClient
        .sql(
            "SELECT (SELECT count(*) FROM office_registration WHERE status = 'PENDING_REVIEW'),"
                + " (SELECT count(*) FROM support_inquiry WHERE assigned_operator_id IS NULL"
                + " AND status <> 'CLOSED'),"
                + " (SELECT count(*) FROM billing_invoice WHERE status = 'VERIFYING'),"
                + " (SELECT count(*) FROM refund_request WHERE status = 'FAILED'),"
                + " (SELECT count(*) FROM import_support_request WHERE status = 'REVIEW'),"
                + " (SELECT count(*) FROM privacy_request WHERE status NOT IN ('COMPLETED',"
                + " 'REJECTED') AND legal_due_at <= :privacyDeadline),"
                + " (SELECT count(*) FROM deletion_queue WHERE cancelled_at IS NULL AND"
                + " ('FAILED' IN (result, storage_result, search_index_result, file_result,"
                + " temporary_export_result))),"
                + " (SELECT count(*) FROM incident_log WHERE status IN ('DETECTED','CONFIRMED',"
                + "'RESPONDING'))")
        .param("privacyDeadline", Timestamp.from(privacyDeadline))
        .query(
            (rs, i) ->
                new QueueCounts(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getLong(3),
                    rs.getLong(4),
                    rs.getLong(5),
                    rs.getLong(6),
                    rs.getLong(7),
                    rs.getLong(8)))
        .single();
  }

  /** 심사 대기 신청마다 마지막으로 심사 대기가 된 시각이다. */
  public List<Instant> pendingReviewReceivedTimes() {
    return jdbcClient
        .sql(
            "SELECT COALESCE((SELECT max(h.created_at) FROM office_registration_status_history h"
                + " WHERE h.registration_id = r.id AND h.to_status = 'PENDING_REVIEW'),"
                + " r.created_at) FROM office_registration r WHERE r.status = 'PENDING_REVIEW'")
        .query((rs, i) -> rs.getTimestamp(1).toInstant())
        .list();
  }

  public Map<LocalDate, Boolean> businessDayOverrides(LocalDate from, LocalDate to) {
    Map<LocalDate, Boolean> overrides = new HashMap<>();
    jdbcClient
        .sql(
            "SELECT calendar_date, is_business_day FROM ops_business_calendar WHERE"
                + " calendar_date >= :from AND calendar_date <= :to")
        .param("from", Date.valueOf(from))
        .param("to", Date.valueOf(to))
        .query(
            (rs, i) ->
                overrides.put(
                    rs.getDate("calendar_date").toLocalDate(), rs.getBoolean("is_business_day")))
        .list();
    return overrides;
  }

  public SubscriptionCounts subscriptionCounts() {
    return jdbcClient
        .sql(
            "SELECT count(*) FILTER (WHERE status = 'TRIAL'), count(*) FILTER (WHERE status ="
                + " 'ACTIVE'), count(*) FILTER (WHERE status IN ('PAST_DUE_GRACE',"
                + "'PAST_DUE_RESTRICTED')), count(*) FILTER (WHERE status = 'ENDED') FROM"
                + " subscription")
        .query(
            (rs, i) ->
                new SubscriptionCounts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)))
        .single();
  }

  /** 기간 안에 수납 승인된 청구액에서 기간 안에 완료된 환불액을 뺀 값이다. */
  public long netCollected(Instant from, Instant to) {
    return jdbcClient
        .sql(
            "SELECT COALESCE((SELECT sum(amount) FROM billing_invoice WHERE paid_at >= :from"
                + " AND paid_at < :to), 0) - COALESCE((SELECT sum(refund_amount) FROM"
                + " refund_request WHERE status = 'COMPLETED' AND resolved_at >= :from AND"
                + " resolved_at < :to), 0)")
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query(Long.class)
        .single();
  }

  // ---- 고객사 ----

  public List<OfficeRow> offices(OfficeFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = officeWhere(filter, params);
    var spec =
        jdbcClient.sql(OFFICE_SELECT + where + " ORDER BY o.id DESC LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit).param("offset", offset).query(this::mapOffice).list();
  }

  public long countOffices(OfficeFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = officeWhere(filter, params);
    var spec =
        jdbcClient.sql(
            "SELECT count(*) FROM office o LEFT JOIN office_registration reg ON"
                + " reg.resulting_office_id = o.id LEFT JOIN subscription s ON s.office_id = o.id"
                + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  private static String officeWhere(OfficeFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.keyword() != null) {
      where.append(
          " AND (lower(o.name) LIKE :kw ESCAPE '\\' OR o.business_registration_number LIKE :kw"
              + " ESCAPE '\\' OR lower(o.real_estate_license_number) LIKE :kw ESCAPE '\\')");
      params.put("kw", like(filter.keyword()));
    }
    if (filter.registrationStatus() != null) {
      where.append(" AND " + REGISTRATION_STATUS + " = :registrationStatus");
      params.put("registrationStatus", filter.registrationStatus());
    }
    if (filter.subscriptionStatus() != null) {
      where.append(" AND s.status = :subscriptionStatus");
      params.put("subscriptionStatus", filter.subscriptionStatus());
    }
    return where.toString();
  }

  public Optional<OfficeRow> office(long officeId) {
    return jdbcClient
        .sql(OFFICE_SELECT + " WHERE o.id = :id")
        .param("id", officeId)
        .query(this::mapOffice)
        .optional();
  }

  public List<HistoryRow> registrationHistory(long officeId) {
    return jdbcClient
        .sql(
            "SELECT h.from_status, h.to_status, h.changed_by_type, h.reason, h.created_at FROM"
                + " office_registration_status_history h JOIN office_registration r ON r.id ="
                + " h.registration_id WHERE r.resulting_office_id = :id ORDER BY h.created_at,"
                + " h.id")
        .param("id", officeId)
        .query(
            (rs, i) ->
                new HistoryRow(
                    rs.getString("from_status"),
                    rs.getString("to_status"),
                    rs.getString("changed_by_type"),
                    rs.getString("reason"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  public List<MemberRow> officeMembers(long officeId) {
    return jdbcClient
        .sql(
            "SELECT m.id, u.name, u.account_status, m.role, m.status FROM office_membership m"
                + " JOIN app_user u ON u.id = m.user_id WHERE m.office_id = :id AND m.status IN"
                + " ('APPROVED','SUSPENDED') ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN"
                + " 'AGENT' THEN 1 ELSE 2 END, m.id")
        .param("id", officeId)
        .query(
            (rs, i) ->
                new MemberRow(
                    rs.getLong("id"),
                    "WITHDRAWN".equals(rs.getString("account_status"))
                        ? "탈퇴한 구성원"
                        : rs.getString("name"),
                    rs.getString("role"),
                    rs.getString("status")))
        .list();
  }

  /** 오늘 유효한 고용명부 인원을 법적 자격별로 센다. */
  public Map<String, Long> rosterCounts(long officeId, LocalDate today) {
    Map<String, Long> counts = new HashMap<>();
    jdbcClient
        .sql(
            "SELECT legal_qualification, count(*) FROM employment_roster WHERE office_id = :id"
                + " AND employment_start_date <= :today AND (employment_end_date IS NULL OR"
                + " employment_end_date >= :today) GROUP BY legal_qualification")
        .param("id", officeId)
        .param("today", Date.valueOf(today))
        .query((rs, i) -> counts.put(rs.getString(1), rs.getLong(2)))
        .list();
    return counts;
  }

  public Optional<SubscriptionRow> subscription(long officeId) {
    return jdbcClient
        .sql(
            "SELECT status, plan, trial_ends_at, current_period_end, past_due_since FROM"
                + " subscription WHERE office_id = :id")
        .param("id", officeId)
        .query(
            (rs, i) ->
                new SubscriptionRow(
                    rs.getString("status"),
                    rs.getString("plan"),
                    instant(rs.getTimestamp("trial_ends_at")),
                    instant(rs.getTimestamp("current_period_end")),
                    instant(rs.getTimestamp("past_due_since"))))
        .optional();
  }

  public long openInquiryCount(long officeId) {
    return jdbcClient
        .sql("SELECT count(*) FROM support_inquiry WHERE office_id = :id AND status <> 'CLOSED'")
        .param("id", officeId)
        .query(Long.class)
        .single();
  }

  /** 교체되지 않은 가장 최근 반환권한이다. */
  public Optional<GrantRow> latestReturnGrant(long officeId) {
    return jdbcClient
        .sql(
            "SELECT id, granted_at, expires_at, revoked_at FROM return_grant WHERE office_id ="
                + " :id AND replaced_by_grant_id IS NULL ORDER BY granted_at DESC, id DESC LIMIT 1")
        .param("id", officeId)
        .query(
            (rs, i) ->
                new GrantRow(
                    rs.getLong("id"),
                    rs.getTimestamp("granted_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    instant(rs.getTimestamp("revoked_at"))))
        .optional();
  }

  public Optional<GrantRow> latestArchiveGrant(long officeId) {
    return jdbcClient
        .sql(
            "SELECT id, granted_at, expires_at, terminated_at FROM archive_grant WHERE office_id"
                + " = :id AND replaced_by_grant_id IS NULL ORDER BY granted_at DESC, id DESC"
                + " LIMIT 1")
        .param("id", officeId)
        .query(
            (rs, i) ->
                new GrantRow(
                    rs.getLong("id"),
                    rs.getTimestamp("granted_at").toInstant(),
                    instant(rs.getTimestamp("expires_at")),
                    instant(rs.getTimestamp("terminated_at"))))
        .optional();
  }

  // ---- 회원 ----

  private static final String CURRENT_OFFICE =
      " LEFT JOIN LATERAL (SELECT m.office_id, m.role FROM office_membership m WHERE m.user_id ="
          + " u.id AND m.status = 'APPROVED' ORDER BY m.id DESC LIMIT 1) cm ON true LEFT JOIN"
          + " office o ON o.id = cm.office_id LEFT JOIN office_registration reg ON"
          + " reg.resulting_office_id = o.id LEFT JOIN subscription s ON s.office_id = o.id";

  public List<UserListRow> users(MemberFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = userWhere(filter, params);
    var spec =
        jdbcClient.sql(
            "SELECT u.id, u.name, u.account_status, o.name AS office_name, u.created_at FROM"
                + " app_user u"
                + CURRENT_OFFICE
                + where
                + " ORDER BY u.id DESC LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, i) ->
                new UserListRow(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("account_status"),
                    rs.getString("office_name"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  public long countUsers(MemberFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = userWhere(filter, params);
    var spec = jdbcClient.sql("SELECT count(*) FROM app_user u" + CURRENT_OFFICE + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  private static String userWhere(MemberFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.keyword() != null) {
      where.append(
          " AND (lower(u.name) LIKE :kw ESCAPE '\\' OR lower(o.name) LIKE :kw ESCAPE '\\' OR"
              + " o.business_registration_number LIKE :kw ESCAPE '\\' OR"
              + " lower(o.real_estate_license_number) LIKE :kw ESCAPE '\\')");
      params.put("kw", like(filter.keyword()));
    }
    if (filter.accountStatus() != null) {
      where.append(" AND u.account_status = :accountStatus");
      params.put("accountStatus", filter.accountStatus());
    }
    if (filter.registrationStatus() != null) {
      where.append(" AND o.id IS NOT NULL AND " + REGISTRATION_STATUS + " = :registrationStatus");
      params.put("registrationStatus", filter.registrationStatus());
    }
    if (filter.subscriptionStatus() != null) {
      where.append(" AND s.status = :subscriptionStatus");
      params.put("subscriptionStatus", filter.subscriptionStatus());
    }
    return where.toString();
  }

  public Optional<UserRow> user(long userId) {
    return jdbcClient
        .sql(
            "SELECT id, name, account_status, withdrawn_at, email IS NOT NULL AS has_email,"
                + " kakao_provider_key IS NOT NULL AS has_kakao, failed_login_locked_until FROM"
                + " app_user WHERE id = :id")
        .param("id", userId)
        .query(
            (rs, i) ->
                new UserRow(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("account_status"),
                    instant(rs.getTimestamp("withdrawn_at")),
                    rs.getBoolean("has_email"),
                    rs.getBoolean("has_kakao"),
                    instant(rs.getTimestamp("failed_login_locked_until"))))
        .optional();
  }

  public Optional<CurrentMembershipRow> currentMembership(long userId) {
    return jdbcClient
        .sql(
            "SELECT m.office_id, o.name, m.role FROM office_membership m JOIN office o ON o.id ="
                + " m.office_id WHERE m.user_id = :id AND m.status = 'APPROVED' ORDER BY m.id"
                + " DESC LIMIT 1")
        .param("id", userId)
        .query(
            (rs, i) ->
                new CurrentMembershipRow(
                    rs.getLong("office_id"), rs.getString("name"), rs.getString("role")))
        .optional();
  }

  public Optional<String> pendingRegistrationStatus(long userId) {
    return jdbcClient
        .sql(
            "SELECT status FROM office_registration WHERE applicant_user_id = :id AND status IN"
                + " ('DRAFT','PENDING_REVIEW','SUPPLEMENT_REQUESTED') ORDER BY id DESC LIMIT 1")
        .param("id", userId)
        .query(String.class)
        .optional();
  }

  public Optional<String> pendingMembershipStatus(long userId) {
    return jdbcClient
        .sql(
            "SELECT status FROM office_membership WHERE user_id = :id AND status = 'PENDING'"
                + " ORDER BY id DESC LIMIT 1")
        .param("id", userId)
        .query(String.class)
        .optional();
  }

  public List<LoginRow> recentLogins(long userId, int limit) {
    return jdbcClient
        .sql(
            "SELECT created_at, user_agent FROM login_history WHERE user_id = :id ORDER BY"
                + " created_at DESC, id DESC LIMIT :limit")
        .param("id", userId)
        .param("limit", limit)
        .query(
            (rs, i) ->
                new LoginRow(rs.getTimestamp("created_at").toInstant(), rs.getString("user_agent")))
        .list();
  }

  public List<SuspensionRow> suspensions(long userId, int limit) {
    return jdbcClient
        .sql(
            "SELECT s.suspended_at, s.scope, s.reason, s.review_at, o.name FROM"
                + " member_emergency_suspension s JOIN operator_account o ON o.id ="
                + " s.suspended_by_operator_id WHERE s.user_id = :id ORDER BY s.suspended_at DESC,"
                + " s.id DESC LIMIT :limit")
        .param("id", userId)
        .param("limit", limit)
        .query(
            (rs, i) ->
                new SuspensionRow(
                    rs.getTimestamp("suspended_at").toInstant(),
                    rs.getString("scope"),
                    rs.getString("reason"),
                    rs.getTimestamp("review_at").toInstant(),
                    rs.getString("name")))
        .list();
  }

  public void insertSuspension(
      long userId,
      String scope,
      String sessionId,
      String reason,
      Instant suspendedAt,
      Instant reviewAt,
      long operatorId) {
    jdbcClient
        .sql(
            "INSERT INTO member_emergency_suspension (user_id, scope, session_id, reason,"
                + " suspended_at, review_at, suspended_by_operator_id) VALUES (:user, :scope,"
                + " :session, :reason, :suspendedAt, :reviewAt, :operator)")
        .param("user", userId)
        .param("scope", scope)
        .param("session", sessionId)
        .param("reason", reason)
        .param("suspendedAt", Timestamp.from(suspendedAt))
        .param("reviewAt", Timestamp.from(reviewAt))
        .param("operator", operatorId)
        .update();
  }

  private static String like(String keyword) {
    String escaped =
        keyword.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private OfficeRow mapOffice(ResultSet rs, int rowNumber) throws SQLException {
    return new OfficeRow(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("representative_name"),
        rs.getString("business_registration_number"),
        rs.getString("real_estate_license_number"),
        rs.getString("representative_phone"),
        rs.getString("registration_authority"),
        rs.getString("address"),
        rs.getString("registration_status"),
        rs.getString("subscription_status"),
        rs.getLong("approved_count"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getLong("version"));
  }
}
