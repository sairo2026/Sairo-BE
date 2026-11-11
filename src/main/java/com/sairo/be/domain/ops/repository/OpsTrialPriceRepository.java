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

/** 요금표 버전·체험 이벤트·영업일 달력의 운영 조회·쓰기다(OPS-08). */
@Profile("!migrate")
@Repository
public class OpsTrialPriceRepository {

  public record PriceFilter(String status, String audience) {}

  public record PriceRow(
      long id,
      String version,
      long soloAmount,
      long teamFirstExtra,
      long teamAfterFourExtra,
      Instant effectiveAt,
      Instant noticeAt,
      String status,
      Long approvedByOperatorId,
      long createdByOperatorId,
      Instant createdAt,
      String audience) {}

  public record NewPrice(
      String version,
      long soloAmount,
      long teamFirstExtra,
      long teamAfterFourExtra,
      Instant effectiveAt,
      Instant noticeAt,
      String audience,
      long operatorId,
      Instant now) {}

  public record EventFilter(String status, String audience) {}

  public record EventRow(
      long id,
      String name,
      Instant startsAt,
      Instant endsAt,
      boolean officeCreatedDuring,
      boolean firstApplicationDuring,
      String freeEndMode,
      Instant fixedFreeEndsAt,
      String status,
      Long approvedByOperatorId,
      long createdByOperatorId,
      Instant createdAt,
      String audience,
      Integer maxOffices) {}

  public record NewEvent(
      String name,
      Instant startsAt,
      Instant endsAt,
      boolean officeCreatedDuring,
      boolean firstApplicationDuring,
      String freeEndMode,
      Instant fixedFreeEndsAt,
      String audience,
      Integer maxOffices,
      long operatorId,
      Instant now) {}

  public record OfficeRef(long id, String name) {}

  public record CalendarRow(LocalDate calendarDate, boolean isBusinessDay, String reason) {}

  private static final String PRICE_COLUMNS =
      "SELECT id, version, solo_amount, team_first_extra, team_after_four_extra, effective_at,"
          + " notice_at, status, approved_by_operator_id, created_by_operator_id, created_at,"
          + " audience FROM price_schedule";

  private static final String EVENT_COLUMNS =
      "SELECT id, name, starts_at, ends_at, office_created_during, first_application_during,"
          + " free_end_mode, fixed_free_ends_at, status, approved_by_operator_id,"
          + " created_by_operator_id, created_at, audience, max_offices FROM trial_event";

  private final JdbcClient jdbcClient;

  public OpsTrialPriceRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  // 요금표

  public List<PriceRow> prices(PriceFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = statusAudienceWhere(filter.status(), filter.audience(), params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            PRICE_COLUMNS
                + where
                + " ORDER BY effective_at DESC, id DESC LIMIT :limit OFFSET :offset")
        .params(params)
        .query(OpsTrialPriceRepository::mapPrice)
        .list();
  }

  public long countPrices(PriceFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = statusAudienceWhere(filter.status(), filter.audience(), params);
    return jdbcClient
        .sql("SELECT count(*) FROM price_schedule" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  public Optional<PriceRow> price(long id) {
    return jdbcClient
        .sql(PRICE_COLUMNS + " WHERE id = :id")
        .param("id", id)
        .query(OpsTrialPriceRepository::mapPrice)
        .optional();
  }

  public Optional<PriceRow> lockPrice(long id) {
    return jdbcClient
        .sql(PRICE_COLUMNS + " WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(OpsTrialPriceRepository::mapPrice)
        .optional();
  }

  /** 버전 문자열 접두어로 시작하는 기존 버전이다. 같은 접두어에 붙일 일련번호를 정하는 데 쓴다. */
  public List<String> versionsStartingWith(String prefix) {
    return jdbcClient
        .sql("SELECT version FROM price_schedule WHERE version LIKE :prefix")
        .param("prefix", prefix + "%")
        .query(String.class)
        .list();
  }

  /** 같은 효력 시각의 예약·시행 표준 요금표가 있는지다(uq_price_schedule_standard_effective와 같은 범위). */
  public boolean liveStandardAt(Instant effectiveAt, Long exceptId) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM price_schedule WHERE audience = 'STANDARD' AND status IN"
                    + " ('SCHEDULED','ACTIVE') AND effective_at = :at AND id <> :except")
            .param("at", Timestamp.from(effectiveAt))
            .param("except", exceptId == null ? -1L : exceptId)
            .query(Long.class)
            .single()
        > 0;
  }

  public long insertPrice(NewPrice price) {
    return jdbcClient
        .sql(
            "INSERT INTO price_schedule (version, solo_amount, team_first_extra,"
                + " team_after_four_extra, effective_at, notice_at, status, created_by_operator_id,"
                + " created_at, audience) VALUES (:version, :solo, :first, :after, :effective,"
                + " :notice, 'DRAFT', :operator, :now, :audience) RETURNING id")
        .param("version", price.version())
        .param("solo", price.soloAmount())
        .param("first", price.teamFirstExtra())
        .param("after", price.teamAfterFourExtra())
        .param("effective", Timestamp.from(price.effectiveAt()))
        .param("notice", Timestamp.from(price.noticeAt()))
        .param("operator", price.operatorId())
        .param("now", Timestamp.from(price.now()))
        .param("audience", price.audience())
        .query(Long.class)
        .single();
  }

  public void approvePrice(long id, String status, long operatorId) {
    jdbcClient
        .sql(
            "UPDATE price_schedule SET status = :status, approved_by_operator_id = :operator"
                + " WHERE id = :id")
        .param("status", status)
        .param("operator", operatorId)
        .param("id", id)
        .update();
  }

  // 체험 이벤트

  public List<EventRow> events(EventFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = statusAudienceWhere(filter.status(), filter.audience(), params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            EVENT_COLUMNS + where + " ORDER BY starts_at DESC, id DESC LIMIT :limit OFFSET :offset")
        .params(params)
        .query(OpsTrialPriceRepository::mapEvent)
        .list();
  }

  public long countEvents(EventFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = statusAudienceWhere(filter.status(), filter.audience(), params);
    return jdbcClient
        .sql("SELECT count(*) FROM trial_event" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  public Optional<EventRow> event(long id) {
    return jdbcClient
        .sql(EVENT_COLUMNS + " WHERE id = :id")
        .param("id", id)
        .query(OpsTrialPriceRepository::mapEvent)
        .optional();
  }

  public Optional<EventRow> lockEvent(long id) {
    return jdbcClient
        .sql(EVENT_COLUMNS + " WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(OpsTrialPriceRepository::mapEvent)
        .optional();
  }

  public long insertEvent(NewEvent event) {
    return jdbcClient
        .sql(
            "INSERT INTO trial_event (name, starts_at, ends_at, office_created_during,"
                + " first_application_during, status, created_by_operator_id, created_at,"
                + " free_end_mode, fixed_free_ends_at, audience, max_offices) VALUES (:name,"
                + " :starts, :ends, :officeCreated, :firstApplication, 'REVIEW', :operator, :now,"
                + " :mode, :fixedEnd, :audience, :maxOffices) RETURNING id")
        .param("name", event.name())
        .param("starts", Timestamp.from(event.startsAt()))
        .param("ends", Timestamp.from(event.endsAt()))
        .param("officeCreated", event.officeCreatedDuring())
        .param("firstApplication", event.firstApplicationDuring())
        .param("operator", event.operatorId())
        .param("now", Timestamp.from(event.now()))
        .param("mode", event.freeEndMode())
        .param(
            "fixedEnd",
            event.fixedFreeEndsAt() == null ? null : Timestamp.from(event.fixedFreeEndsAt()))
        .param("audience", event.audience())
        .param("maxOffices", event.maxOffices())
        .query(Long.class)
        .single();
  }

  /** 다른 예약·시행 일반 이벤트와 가입 창(시작 포함, 종료 제외)이 겹치는지다(ex_trial_event_general_window와 같은 범위). */
  public boolean generalWindowOverlaps(long eventId, Instant startsAt, Instant endsAt) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM trial_event WHERE audience = 'GENERAL' AND status IN"
                    + " ('SCHEDULED','ACTIVE') AND id <> :id AND starts_at < :ends AND ends_at >"
                    + " :starts")
            .param("id", eventId)
            .param("starts", Timestamp.from(startsAt))
            .param("ends", Timestamp.from(endsAt))
            .query(Long.class)
            .single()
        > 0;
  }

  public void approveEvent(long id, String status, long operatorId) {
    jdbcClient
        .sql(
            "UPDATE trial_event SET status = :status, approved_by_operator_id = :operator"
                + " WHERE id = :id")
        .param("status", status)
        .param("operator", operatorId)
        .param("id", id)
        .update();
  }

  public void stopEvent(long id, Instant endsAt) {
    jdbcClient
        .sql("UPDATE trial_event SET status = 'ENDED', ends_at = :ends WHERE id = :id")
        .param("ends", Timestamp.from(endsAt))
        .param("id", id)
        .update();
  }

  /**
   * 일반 이벤트의 조건을 만족하는 사무소다. 기간 내 사무소 생성은 office.created_at, 기간 내 최초 인증 신청은 그 사무소로 이어진 가장 이른 인증 신청의
   * created_at으로 본다. 두 조건이 모두 꺼져 있으면 모든 사무소다.
   */
  public List<OfficeRef> generalTargets(EventRow event) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT o.id, o.name FROM office o LEFT JOIN (SELECT resulting_office_id,"
                + " min(created_at) AS first_at FROM office_registration WHERE resulting_office_id"
                + " IS NOT NULL GROUP BY resulting_office_id) r ON r.resulting_office_id = o.id"
                + " WHERE 1 = 1");
    if (event.officeCreatedDuring()) {
      sql.append(" AND o.created_at >= :starts AND o.created_at < :ends");
    }
    if (event.firstApplicationDuring()) {
      sql.append(" AND r.first_at >= :starts AND r.first_at < :ends");
    }
    sql.append(" ORDER BY o.id");
    return jdbcClient
        .sql(sql.toString())
        .param("starts", Timestamp.from(event.startsAt()))
        .param("ends", Timestamp.from(event.endsAt()))
        .query((rs, n) -> new OfficeRef(rs.getLong("id"), rs.getString("name")))
        .list();
  }

  /** 시작 고객 이벤트의 대상은 이 이벤트에 등록·승인된 합의가 있는 사무소다. */
  public List<OfficeRef> startCustomerTargets(long eventId) {
    return jdbcClient
        .sql(
            "SELECT o.id, o.name FROM office o WHERE EXISTS (SELECT 1 FROM"
                + " billing_start_customer_agreement a WHERE a.office_id = o.id AND"
                + " a.trial_event_id = :id AND a.status IN ('REGISTERED','APPROVED')) ORDER BY"
                + " o.id")
        .param("id", eventId)
        .query((rs, n) -> new OfficeRef(rs.getLong("id"), rs.getString("name")))
        .list();
  }

  /** 다른 이벤트로 이미 체험을 받은 사무소다. 구독의 trial_event_id가 수혜 근거다. */
  public List<Long> officesWithOtherTrial(long eventId) {
    return jdbcClient
        .sql(
            "SELECT office_id FROM subscription WHERE trial_event_id IS NOT NULL AND"
                + " trial_event_id <> :id")
        .param("id", eventId)
        .query(Long.class)
        .list();
  }

  // 영업일 달력

  public List<CalendarRow> calendar(LocalDate from, LocalDate to, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = calendarWhere(from, to, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            "SELECT calendar_date, is_business_day, reason FROM ops_business_calendar"
                + where
                + " ORDER BY calendar_date LIMIT :limit OFFSET :offset")
        .params(params)
        .query(
            (rs, n) ->
                new CalendarRow(
                    rs.getObject("calendar_date", LocalDate.class),
                    rs.getBoolean("is_business_day"),
                    rs.getString("reason")))
        .list();
  }

  public long countCalendar(LocalDate from, LocalDate to) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = calendarWhere(from, to, params);
    return jdbcClient
        .sql("SELECT count(*) FROM ops_business_calendar" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  public void upsertCalendar(
      LocalDate date, boolean businessDay, String reason, long operatorId, Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO ops_business_calendar (calendar_date, is_business_day, reason,"
                + " updated_by_operator_id, updated_at) VALUES (:date, :business, :reason,"
                + " :operator, :now) ON CONFLICT (calendar_date) DO UPDATE SET is_business_day ="
                + " EXCLUDED.is_business_day, reason = EXCLUDED.reason, updated_by_operator_id ="
                + " EXCLUDED.updated_by_operator_id, updated_at = EXCLUDED.updated_at")
        .param("date", Date.valueOf(date))
        .param("business", businessDay)
        .param("reason", reason)
        .param("operator", operatorId)
        .param("now", Timestamp.from(now))
        .update();
  }

  private static String calendarWhere(LocalDate from, LocalDate to, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (from != null) {
      where.append(" AND calendar_date >= :from");
      params.put("from", Date.valueOf(from));
    }
    if (to != null) {
      where.append(" AND calendar_date <= :to");
      params.put("to", Date.valueOf(to));
    }
    return where.toString();
  }

  private static String statusAudienceWhere(
      String status, String audience, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (status != null) {
      where.append(" AND status = :status");
      params.put("status", status);
    }
    if (audience != null) {
      where.append(" AND audience = :audience");
      params.put("audience", audience);
    }
    return where.toString();
  }

  private static PriceRow mapPrice(ResultSet rs, int n) throws SQLException {
    return new PriceRow(
        rs.getLong("id"),
        rs.getString("version"),
        rs.getLong("solo_amount"),
        rs.getLong("team_first_extra"),
        rs.getLong("team_after_four_extra"),
        instant(rs, "effective_at"),
        instant(rs, "notice_at"),
        rs.getString("status"),
        rs.getObject("approved_by_operator_id", Long.class),
        rs.getLong("created_by_operator_id"),
        instant(rs, "created_at"),
        rs.getString("audience"));
  }

  private static EventRow mapEvent(ResultSet rs, int n) throws SQLException {
    return new EventRow(
        rs.getLong("id"),
        rs.getString("name"),
        instant(rs, "starts_at"),
        instant(rs, "ends_at"),
        rs.getBoolean("office_created_during"),
        rs.getBoolean("first_application_during"),
        rs.getString("free_end_mode"),
        instant(rs, "fixed_free_ends_at"),
        rs.getString("status"),
        rs.getObject("approved_by_operator_id", Long.class),
        rs.getLong("created_by_operator_id"),
        instant(rs, "created_at"),
        rs.getString("audience"),
        rs.getObject("max_offices", Integer.class));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
