package com.sairo.be.domain.ops.repository;

import static com.sairo.be.domain.ops.repository.OpsSystemRepository.instant;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 약관 원문 해시 점검·보관 기간 규칙·카카오 연결 끊기 사건의 운영 조회·쓰기다(OPS-18·19). */
@Profile("!migrate")
@Repository
public class OpsLegalOpsRepository {

  private final JdbcClient jdbcClient;

  public OpsLegalOpsRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  // 약관 해시 점검

  public record TermsRow(
      String termsCode,
      String version,
      String documentUrl,
      String contentHash,
      Instant effectiveAt,
      Instant lastCheckedAt,
      String lastResult) {}

  /** 시행 예정 버전 전부와, 약관 코드마다 시행 시각이 지난 가장 늦은 버전이다. */
  public List<TermsRow> liveTerms(Instant now) {
    return jdbcClient
        .sql(
            "SELECT t.terms_code, t.terms_version, t.document_url, t.content_hash, t.effective_at,"
                + " t.last_hash_checked_at, t.last_hash_check_result FROM terms_catalog t WHERE"
                + " t.effective_at > :now OR t.effective_at = (SELECT max(x.effective_at) FROM"
                + " terms_catalog x WHERE x.terms_code = t.terms_code AND x.effective_at <= :now)"
                + " ORDER BY t.terms_code, t.effective_at, t.terms_version")
        .param("now", Timestamp.from(now))
        .query(
            (rs, i) ->
                new TermsRow(
                    rs.getString("terms_code"),
                    rs.getString("terms_version"),
                    rs.getString("document_url"),
                    rs.getString("content_hash"),
                    instant(rs, "effective_at"),
                    instant(rs, "last_hash_checked_at"),
                    rs.getString("last_hash_check_result")))
        .list();
  }

  public void recordHashCheck(String termsCode, String version, String result, Instant now) {
    jdbcClient
        .sql(
            "UPDATE terms_catalog SET last_hash_checked_at = :now, last_hash_check_result ="
                + " :result WHERE terms_code = :code AND terms_version = :version")
        .param("now", Timestamp.from(now))
        .param("result", result)
        .param("code", termsCode)
        .param("version", version)
        .update();
  }

  // 보관 기간 규칙

  public record RuleRow(
      long id,
      String ruleCode,
      String dataGroupLabel,
      int retentionDays,
      String anchorEvent,
      String basisNote,
      String status,
      LocalDate effectiveFrom,
      long version) {}

  private static final String RULE_SELECT =
      "SELECT id, rule_code, data_group_label, retention_days, anchor_event, basis_note, status,"
          + " effective_from, version FROM legal_retention_rule";

  public List<RuleRow> rules() {
    return jdbcClient.sql(RULE_SELECT + " ORDER BY rule_code").query(this::rule).list();
  }

  public Optional<RuleRow> lockedRule(String ruleCode) {
    return jdbcClient
        .sql(RULE_SELECT + " WHERE rule_code = :code FOR UPDATE")
        .param("code", ruleCode)
        .query(this::rule)
        .optional();
  }

  public record RuleChange(
      int retentionDays,
      String basisNote,
      String status,
      LocalDate effectiveFrom,
      long operatorId,
      Instant now) {}

  /** 버전이 맞을 때만 바꾸고 버전을 1 올린다. 바꾼 행이 없으면 비어 있다. */
  public Optional<RuleRow> updateRule(String ruleCode, long expectedVersion, RuleChange change) {
    return jdbcClient
        .sql(
            "UPDATE legal_retention_rule SET retention_days = :days, basis_note = :basis, status ="
                + " :status, effective_from = :effectiveFrom, updated_by_operator_id = :operator,"
                + " updated_at = :now, version = version + 1 WHERE rule_code = :code AND version ="
                + " :version RETURNING id, rule_code, data_group_label, retention_days,"
                + " anchor_event, basis_note, status, effective_from, version")
        .param("days", change.retentionDays())
        .param("basis", change.basisNote())
        .param("status", change.status())
        .param("effectiveFrom", Date.valueOf(change.effectiveFrom()))
        .param("operator", change.operatorId())
        .param("now", Timestamp.from(change.now()))
        .param("code", ruleCode)
        .param("version", expectedVersion)
        .query(this::rule)
        .optional();
  }

  private RuleRow rule(ResultSet rs, int rowNum) throws SQLException {
    return new RuleRow(
        rs.getLong("id"),
        rs.getString("rule_code"),
        rs.getString("data_group_label"),
        rs.getInt("retention_days"),
        rs.getString("anchor_event"),
        rs.getString("basis_note"),
        rs.getString("status"),
        rs.getDate("effective_from").toLocalDate(),
        rs.getLong("version"));
  }

  // 카카오 연결 끊기 사건

  public record UnlinkRow(
      long id,
      String direction,
      String status,
      int attemptCount,
      Instant nextAttemptAt,
      String lastErrorCode,
      String referrerType,
      Long userId,
      Instant occurredAt,
      Instant completedAt) {}

  private static final String UNLINK_SELECT =
      "SELECT id, direction, status, attempt_count, next_attempt_at, last_error_code,"
          + " referrer_type, user_id, occurred_at, completed_at FROM legal_kakao_unlink_event";

  private static final String UNLINK_FILTER =
      " WHERE (CAST(:direction AS VARCHAR) IS NULL OR direction = :direction)"
          + " AND (CAST(:status AS VARCHAR) IS NULL OR status = :status)";

  public List<UnlinkRow> unlinkEvents(String direction, String status, int limit, long offset) {
    return jdbcClient
        .sql(
            UNLINK_SELECT
                + UNLINK_FILTER
                + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset")
        .param("direction", direction)
        .param("status", status)
        .param("limit", limit)
        .param("offset", offset)
        .query(this::unlink)
        .list();
  }

  public long countUnlinkEvents(String direction, String status) {
    return jdbcClient
        .sql("SELECT count(*) FROM legal_kakao_unlink_event" + UNLINK_FILTER)
        .param("direction", direction)
        .param("status", status)
        .query(Long.class)
        .single();
  }

  public Optional<UnlinkRow> lockedUnlinkEvent(long id) {
    return jdbcClient
        .sql(UNLINK_SELECT + " WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(this::unlink)
        .optional();
  }

  /** 대기·재시도 상태의 호출 작업을 지금 다시 시도하도록 대기로 돌린다. 시도 횟수는 실제 호출 때 오른다. */
  public void scheduleRetry(long id, Instant now) {
    jdbcClient
        .sql(
            "UPDATE legal_kakao_unlink_event SET status = 'PENDING', next_attempt_at = :now"
                + " WHERE id = :id AND direction = 'OUTBOUND_CALL' AND status IN"
                + " ('PENDING','FAILED_RETRYING')")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  private UnlinkRow unlink(ResultSet rs, int rowNum) throws SQLException {
    return new UnlinkRow(
        rs.getLong("id"),
        rs.getString("direction"),
        rs.getString("status"),
        rs.getInt("attempt_count"),
        instant(rs, "next_attempt_at"),
        rs.getString("last_error_code"),
        rs.getString("referrer_type"),
        rs.getObject("user_id", Long.class),
        instant(rs, "occurred_at"),
        instant(rs, "completed_at"));
  }
}
