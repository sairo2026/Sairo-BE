package com.sairo.be.domain.legal.service;

import com.sairo.be.global.support.TimeRules;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 보관 기한이 지난 데이터군의 파기 큐 대상이다(기술설계 3.18, OPS-13). 비회원 링크 입력과 승인자 개인정보는 사무소 단위, 로그인 기록과 카카오 연결 끊기 이력은
 * 전체 단위(대상 ID 0) 한 건으로 큐에 오른다. 대상 건수는 실행 시점의 기한으로 다시 센다. 승인자 개인정보는 행을 지우지 않고 익명화한다.
 */
@Profile("!migrate")
@Component
public class RetentionDeletionTargets {

  public static final String GUEST_LINK_INPUT = "GUEST_LINK_INPUT";
  public static final String APPROVER_PERSONAL = "APPROVER_PERSONAL";
  public static final String LOGIN_HISTORY = "LOGIN_HISTORY";
  public static final String KAKAO_UNLINK_EVENT = "KAKAO_UNLINK_EVENT";
  public static final long GLOBAL_TARGET_ID = 0L;
  public static final String RETENTION_PURGE = "RETENTION_PURGE";

  static final Set<String> OFFICE_TYPES = Set.of(GUEST_LINK_INPUT, APPROVER_PERSONAL);
  static final Set<String> GLOBAL_TYPES = Set.of(LOGIN_HISTORY, KAKAO_UNLINK_EVENT);

  private static final String GUEST_LINK_WHERE =
      " FROM visit_customer_link l JOIN visit_coordination c ON c.id = l.coordination_id WHERE"
          + " c.office_id = :office AND l.retention_until < :today";
  private static final String APPROVAL_WHERE =
      " FROM property_access_approval a JOIN property p ON p.id = a.property_id WHERE p.office_id ="
          + " :office AND a.anonymized_at IS NULL AND a.retention_until < :today AND"
          + " (a.revoked_at IS NOT NULL OR a.valid_until <= :now)";

  private final JdbcClient jdbcClient;
  private final RetentionRuleLookup ruleLookup;

  public RetentionDeletionTargets(JdbcClient jdbcClient, RetentionRuleLookup ruleLookup) {
    this.jdbcClient = jdbcClient;
    this.ruleLookup = ruleLookup;
  }

  public boolean supports(String targetType) {
    return OFFICE_TYPES.contains(targetType) || GLOBAL_TYPES.contains(targetType);
  }

  /** 지금 기한이 지난 대상 행 수다. 규칙이 없는 전체 단위 데이터군은 0이다. */
  public long dueCount(String targetType, long targetId, Instant now) {
    return switch (targetType) {
      case GUEST_LINK_INPUT -> officeCount("SELECT count(*)" + GUEST_LINK_WHERE, targetId, now);
      case APPROVER_PERSONAL -> officeCount("SELECT count(*)" + APPROVAL_WHERE, targetId, now);
      case LOGIN_HISTORY ->
          cutoff("LOGIN_HISTORY", now)
              .map(
                  at ->
                      jdbcClient
                          .sql("SELECT count(*) FROM login_history WHERE created_at < :cutoff")
                          .param("cutoff", at)
                          .query(Long.class)
                          .single())
              .orElse(0L);
      case KAKAO_UNLINK_EVENT ->
          cutoff("KAKAO_UNLINK_EVENT", now)
              .map(
                  at ->
                      jdbcClient
                          .sql(
                              "SELECT count(*) FROM legal_kakao_unlink_event WHERE completed_at IS"
                                  + " NOT NULL AND completed_at < :cutoff")
                          .param("cutoff", at)
                          .query(Long.class)
                          .single())
              .orElse(0L);
      default -> 0L;
    };
  }

  /** 사무소 단위 데이터군에서 기한이 지난 행이 있는 사무소와 그 건수다. */
  public record OfficeDue(long officeId, long count) {}

  public List<OfficeDue> dueOffices(String targetType, Instant now) {
    String from =
        switch (targetType) {
          case GUEST_LINK_INPUT -> GUEST_LINK_WHERE.replace("c.office_id = :office AND ", "");
          case APPROVER_PERSONAL -> APPROVAL_WHERE.replace("p.office_id = :office AND ", "");
          default -> throw new IllegalArgumentException("사무소 단위 데이터군이 아니다: " + targetType);
        };
    String officeColumn = GUEST_LINK_INPUT.equals(targetType) ? "c.office_id" : "p.office_id";
    var spec =
        jdbcClient
            .sql(
                "SELECT "
                    + officeColumn
                    + " AS office_id, count(*) AS due"
                    + from
                    + " GROUP BY "
                    + officeColumn
                    + " ORDER BY "
                    + officeColumn)
            .param("today", today(now));
    if (from.contains(":now")) {
      spec = spec.param("now", Timestamp.from(now));
    }
    return spec.query((rs, i) -> new OfficeDue(rs.getLong("office_id"), rs.getLong("due"))).list();
  }

  /** 기한이 지난 대상을 지우거나 익명화하고 처리한 행 수를 돌려준다. 호출한 쪽 트랜잭션에서 실행한다. */
  public long execute(String targetType, long targetId, Instant now) {
    return switch (targetType) {
      case GUEST_LINK_INPUT ->
          jdbcClient
              .sql(
                  "DELETE FROM visit_customer_link l USING visit_coordination c WHERE c.id ="
                      + " l.coordination_id AND c.office_id = :office AND l.retention_until <"
                      + " :today")
              .param("office", targetId)
              .param("today", today(now))
              .update();
      case APPROVER_PERSONAL -> anonymizeApprovals(targetId, now);
      case LOGIN_HISTORY ->
          cutoff("LOGIN_HISTORY", now)
              .map(
                  at ->
                      (long)
                          jdbcClient
                              .sql("DELETE FROM login_history WHERE created_at < :cutoff")
                              .param("cutoff", at)
                              .update())
              .orElse(0L);
      case KAKAO_UNLINK_EVENT ->
          cutoff("KAKAO_UNLINK_EVENT", now)
              .map(
                  at ->
                      (long)
                          jdbcClient
                              .sql(
                                  "DELETE FROM legal_kakao_unlink_event WHERE completed_at IS NOT"
                                      + " NULL AND completed_at < :cutoff")
                              .param("cutoff", at)
                              .update())
              .orElse(0L);
      default -> throw new IllegalArgumentException("처리기가 없는 파기 대상 종류다: " + targetType);
    };
  }

  /**
   * 출입승인의 승인자 이름·연락처·연락처 해시를 비우고 익명화 시각을 남긴다. 출입승인 행과 시간 규칙 값은 신청 판정 이력의 근거라 그대로 두고, 승인자 조건의 입력 계정
   * 연결만 끊고, 활성 프로필 연결은 해제로 바꿔 인증 사건을 남긴다.
   */
  private long anonymizeApprovals(long officeId, Instant now) {
    List<Long> ids =
        jdbcClient
            .sql("SELECT a.id" + APPROVAL_WHERE + " ORDER BY a.id FOR UPDATE OF a")
            .param("office", officeId)
            .param("today", today(now))
            .param("now", Timestamp.from(now))
            .query(Long.class)
            .list();
    if (ids.isEmpty()) {
      return 0;
    }
    Timestamp at = Timestamp.from(now);
    jdbcClient
        .sql(
            "UPDATE visit_approver_condition SET entered_by_profile_id = NULL WHERE approval_id IN"
                + " (:ids) AND entered_by_profile_id IS NOT NULL")
        .param("ids", ids)
        .update();
    List<long[]> links =
        jdbcClient
            .sql(
                "UPDATE approver_profile_approval SET status = 'UNLINKED', decided_at ="
                    + " COALESCE(decided_at, :at), unlinked_at = :at WHERE approval_id IN (:ids) AND"
                    + " status IN ('PENDING_CONFIRM','LINKED') RETURNING profile_id, approval_id")
            .param("at", at)
            .param("ids", ids)
            .query((rs, i) -> new long[] {rs.getLong("profile_id"), rs.getLong("approval_id")})
            .list();
    for (long[] link : links) {
      jdbcClient
          .sql(
              "INSERT INTO approver_auth_event (profile_id, approval_id, event_type, reason,"
                  + " occurred_at) VALUES (:profile, :approval, 'APPROVAL_UNLINKED', :reason, :at)")
          .param("profile", link[0])
          .param("approval", link[1])
          .param("reason", RETENTION_PURGE)
          .param("at", at)
          .update();
    }
    return jdbcClient
        .sql(
            "UPDATE property_access_approval SET approver_name = NULL, approver_phone = NULL,"
                + " approver_phone_hash = NULL, anonymized_at = :at WHERE id IN (:ids)")
        .param("at", at)
        .param("ids", ids)
        .update();
  }

  private long officeCount(String sql, long officeId, Instant now) {
    var spec = jdbcClient.sql(sql).param("office", officeId).param("today", today(now));
    if (sql.contains(":now")) {
      spec = spec.param("now", Timestamp.from(now));
    }
    return spec.query(Long.class).single();
  }

  private java.util.Optional<Timestamp> cutoff(String ruleCode, Instant now) {
    OptionalInt days = ruleLookup.retentionDays(ruleCode);
    return days.isPresent()
        ? java.util.Optional.of(Timestamp.from(now.minus(Duration.ofDays(days.getAsInt()))))
        : java.util.Optional.empty();
  }

  static Date today(Instant now) {
    return Date.valueOf(TimeRules.displayDate(now));
  }
}
