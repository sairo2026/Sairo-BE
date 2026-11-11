package com.sairo.be.domain.ops.repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 시작 고객 합의와 가격 변경 안내 현황의 운영 조회·쓰기다(OPS-21·22). */
@Profile("!migrate")
@Repository
public class OpsPricingRepository {

  public record AgreementFilter(String status, Long trialEventId, Long officeId) {}

  public record AgreementRow(
      long id,
      long officeId,
      String officeName,
      String status,
      long trialEventId,
      long priceScheduleId,
      String priceVersion,
      Instant priceStartsAt,
      Instant priceEndsAt,
      LocalDate contractedOn,
      String contractReference,
      long createdByOperatorId,
      Long approvedByOperatorId,
      Instant approvedAt,
      Long revokedByOperatorId,
      Instant revokedAt,
      Instant ownerAcknowledgedAt,
      Instant createdAt,
      long version) {}

  public record CohortRow(
      long trialEventId, String eventName, long approvedCount, Integer maxOffices) {}

  public record EventRow(
      long id,
      String audience,
      String status,
      Instant startsAt,
      Instant endsAt,
      Instant fixedFreeEndsAt,
      Integer maxOffices) {}

  public record PriceRow(long id, String audience, String status, String version) {}

  public record NewAgreement(
      long officeId,
      long trialEventId,
      long priceScheduleId,
      Instant priceStartsAt,
      Instant priceEndsAt,
      LocalDate contractedOn,
      String contractReference,
      boolean casePublicationConsent,
      String agreementTextVersion,
      long operatorId,
      Instant now) {}

  public record NoticeFilter(Long priceScheduleId, String decision, Long officeId) {}

  public record NoticeRow(
      long id,
      long officeId,
      String officeName,
      long subscriptionId,
      String changeKind,
      String fromVersion,
      String toVersion,
      Instant notifiedAt,
      Instant effectiveAt,
      Instant responseDueAt,
      String decision,
      Instant decidedAt) {}

  private static final String AGREEMENT_COLUMNS =
      "SELECT a.id, a.office_id, o.name AS office_name, a.status, a.trial_event_id,"
          + " a.price_schedule_id, p.version AS price_version, a.price_starts_at, a.price_ends_at,"
          + " a.contracted_on, a.contract_reference, a.created_by_operator_id,"
          + " a.approved_by_operator_id, a.approved_at, a.revoked_by_operator_id, a.revoked_at,"
          + " a.owner_acknowledged_at, a.created_at, a.version"
          + " FROM billing_start_customer_agreement a JOIN office o ON o.id = a.office_id"
          + " JOIN price_schedule p ON p.id = a.price_schedule_id";

  private static final String NOTICE_COLUMNS =
      "SELECT n.id, n.office_id, o.name AS office_name, n.subscription_id, n.change_kind,"
          + " f.version AS from_version, t.version AS to_version, n.notified_at, n.effective_at,"
          + " n.response_due_at, n.decision, n.decided_at FROM pricing_change_notice n"
          + " JOIN office o ON o.id = n.office_id"
          + " JOIN price_schedule f ON f.id = n.from_price_schedule_id"
          + " JOIN price_schedule t ON t.id = n.to_price_schedule_id";

  private final JdbcClient jdbcClient;

  public OpsPricingRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  // 시작 고객 합의

  public List<AgreementRow> agreements(AgreementFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = agreementWhere(filter, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            AGREEMENT_COLUMNS
                + where
                + " ORDER BY a.created_at DESC, a.id DESC LIMIT :limit OFFSET :offset")
        .params(params)
        .query(OpsPricingRepository::mapAgreement)
        .list();
  }

  public long countAgreements(AgreementFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = agreementWhere(filter, params);
    return jdbcClient
        .sql("SELECT count(*) FROM billing_start_customer_agreement a" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  private static String agreementWhere(AgreementFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.status() != null) {
      where.append(" AND a.status = :status");
      params.put("status", filter.status());
    }
    if (filter.trialEventId() != null) {
      where.append(" AND a.trial_event_id = :eventId");
      params.put("eventId", filter.trialEventId());
    }
    if (filter.officeId() != null) {
      where.append(" AND a.office_id = :officeId");
      params.put("officeId", filter.officeId());
    }
    return where.toString();
  }

  /** 시작 고객 이벤트별 승인 합의 수다. 이벤트를 지정하면 그 이벤트만 본다. */
  public List<CohortRow> cohorts(Long trialEventId) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = " WHERE e.audience = 'START_CUSTOMER'";
    if (trialEventId != null) {
      where += " AND e.id = :eventId";
      params.put("eventId", trialEventId);
    }
    return jdbcClient
        .sql(
            "SELECT e.id, e.name, e.max_offices, (SELECT count(*) FROM"
                + " billing_start_customer_agreement a WHERE a.trial_event_id = e.id AND a.status"
                + " = 'APPROVED') AS approved_count FROM trial_event e"
                + where
                + " ORDER BY e.starts_at, e.id")
        .params(params)
        .query(
            (rs, n) ->
                new CohortRow(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getLong("approved_count"),
                    rs.getObject("max_offices", Integer.class)))
        .list();
  }

  public Optional<AgreementRow> agreement(long id) {
    return jdbcClient
        .sql(AGREEMENT_COLUMNS + " WHERE a.id = :id")
        .param("id", id)
        .query(OpsPricingRepository::mapAgreement)
        .optional();
  }

  public Optional<AgreementRow> lockAgreement(long id) {
    return jdbcClient
        .sql(AGREEMENT_COLUMNS + " WHERE a.id = :id FOR UPDATE OF a")
        .param("id", id)
        .query(OpsPricingRepository::mapAgreement)
        .optional();
  }

  public boolean hasLiveAgreement(long officeId) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM billing_start_customer_agreement WHERE office_id = :office"
                    + " AND status IN ('REGISTERED','APPROVED')")
            .param("office", officeId)
            .query(Long.class)
            .single()
        > 0;
  }

  public long countApproved(long trialEventId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM billing_start_customer_agreement WHERE trial_event_id = :id"
                + " AND status = 'APPROVED'")
        .param("id", trialEventId)
        .query(Long.class)
        .single();
  }

  public Optional<EventRow> event(long id) {
    return jdbcClient
        .sql(
            "SELECT id, audience, status, starts_at, ends_at, fixed_free_ends_at, max_offices"
                + " FROM trial_event WHERE id = :id")
        .param("id", id)
        .query(
            (rs, n) ->
                new EventRow(
                    rs.getLong("id"),
                    rs.getString("audience"),
                    rs.getString("status"),
                    instant(rs, "starts_at"),
                    instant(rs, "ends_at"),
                    instant(rs, "fixed_free_ends_at"),
                    rs.getObject("max_offices", Integer.class)))
        .optional();
  }

  public Optional<PriceRow> price(long id) {
    return jdbcClient
        .sql("SELECT id, audience, status, version FROM price_schedule WHERE id = :id")
        .param("id", id)
        .query(
            (rs, n) ->
                new PriceRow(
                    rs.getLong("id"),
                    rs.getString("audience"),
                    rs.getString("status"),
                    rs.getString("version")))
        .optional();
  }

  public boolean officeExists(long officeId) {
    return jdbcClient
            .sql("SELECT count(*) FROM office WHERE id = :id")
            .param("id", officeId)
            .query(Long.class)
            .single()
        > 0;
  }

  public long insertAgreement(NewAgreement agreement) {
    return jdbcClient
        .sql(
            "INSERT INTO billing_start_customer_agreement (office_id, trial_event_id,"
                + " price_schedule_id, price_starts_at, price_ends_at, status, contracted_on,"
                + " contract_reference, weekly_use_confirmed, monthly_interview_confirmed,"
                + " case_publication_consent, agreement_text_version, created_by_operator_id,"
                + " created_at, updated_at) VALUES (:office, :event, :price, :starts, :ends,"
                + " 'REGISTERED', :contractedOn, :reference, true, true, :casePublication,"
                + " :textVersion, :operator, :now, :now) RETURNING id")
        .param("office", agreement.officeId())
        .param("event", agreement.trialEventId())
        .param("price", agreement.priceScheduleId())
        .param("starts", Timestamp.from(agreement.priceStartsAt()))
        .param("ends", Timestamp.from(agreement.priceEndsAt()))
        .param("contractedOn", Date.valueOf(agreement.contractedOn()))
        .param("reference", agreement.contractReference())
        .param("casePublication", agreement.casePublicationConsent())
        .param("textVersion", agreement.agreementTextVersion())
        .param("operator", agreement.operatorId())
        .param("now", Timestamp.from(agreement.now()))
        .query(Long.class)
        .single();
  }

  public void approve(long id, long operatorId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE billing_start_customer_agreement SET status = 'APPROVED',"
                + " approved_by_operator_id = :operator, approved_at = :now, updated_at = :now,"
                + " version = version + 1 WHERE id = :id")
        .param("operator", operatorId)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void revoke(long id, long operatorId, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE billing_start_customer_agreement SET status = 'REVOKED',"
                + " revoked_by_operator_id = :operator, revoked_at = :now, revoke_reason = :reason,"
                + " updated_at = :now, version = version + 1 WHERE id = :id")
        .param("operator", operatorId)
        .param("now", Timestamp.from(now))
        .param("reason", reason)
        .param("id", id)
        .update();
  }

  public record SubscriptionState(
      long id, String status, Instant trialEndsAt, Instant firstBillingScheduledAt) {}

  public Optional<SubscriptionState> subscription(long officeId) {
    return jdbcClient
        .sql(
            "SELECT id, status, trial_ends_at, first_billing_scheduled_at FROM subscription"
                + " WHERE office_id = :office")
        .param("office", officeId)
        .query(
            (rs, n) ->
                new SubscriptionState(
                    rs.getLong("id"),
                    rs.getString("status"),
                    instant(rs, "trial_ends_at"),
                    instant(rs, "first_billing_scheduled_at")))
        .optional();
  }

  // 가격 변경 안내

  public List<NoticeRow> notices(NoticeFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = noticeWhere(filter, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            NOTICE_COLUMNS
                + where
                + " ORDER BY n.notified_at DESC, n.id DESC LIMIT :limit OFFSET :offset")
        .params(params)
        .query(
            (rs, n) ->
                new NoticeRow(
                    rs.getLong("id"),
                    rs.getLong("office_id"),
                    rs.getString("office_name"),
                    rs.getLong("subscription_id"),
                    rs.getString("change_kind"),
                    rs.getString("from_version"),
                    rs.getString("to_version"),
                    instant(rs, "notified_at"),
                    instant(rs, "effective_at"),
                    instant(rs, "response_due_at"),
                    rs.getString("decision"),
                    instant(rs, "decided_at")))
        .list();
  }

  /** 필터를 적용한 전체 결과의 결정별 건수다. */
  public Map<String, Long> noticeSummary(NoticeFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = noticeWhere(filter, params);
    Map<String, Long> counts = new LinkedHashMap<>();
    jdbcClient
        .sql(
            "SELECT n.decision, count(*) AS cnt FROM pricing_change_notice n"
                + where
                + " GROUP BY n.decision")
        .params(params)
        .query(
            (rs, n) -> {
              counts.put(rs.getString("decision"), rs.getLong("cnt"));
              return null;
            })
        .list();
    return counts;
  }

  private static String noticeWhere(NoticeFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.priceScheduleId() != null) {
      where.append(" AND n.to_price_schedule_id = :scheduleId");
      params.put("scheduleId", filter.priceScheduleId());
    }
    if (filter.decision() != null) {
      where.append(" AND n.decision = :decision");
      params.put("decision", filter.decision());
    }
    if (filter.officeId() != null) {
      where.append(" AND n.office_id = :officeId");
      params.put("officeId", filter.officeId());
    }
    return where.toString();
  }

  private static AgreementRow mapAgreement(ResultSet rs, int n) throws SQLException {
    return new AgreementRow(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getString("office_name"),
        rs.getString("status"),
        rs.getLong("trial_event_id"),
        rs.getLong("price_schedule_id"),
        rs.getString("price_version"),
        instant(rs, "price_starts_at"),
        instant(rs, "price_ends_at"),
        rs.getObject("contracted_on", LocalDate.class),
        rs.getString("contract_reference"),
        rs.getLong("created_by_operator_id"),
        rs.getObject("approved_by_operator_id", Long.class),
        instant(rs, "approved_at"),
        rs.getObject("revoked_by_operator_id", Long.class),
        instant(rs, "revoked_at"),
        instant(rs, "owner_acknowledged_at"),
        instant(rs, "created_at"),
        rs.getLong("version"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
