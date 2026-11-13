package com.sairo.be.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class ConsentNoticeBatchRepository {

  public record DueNotice(
      long id,
      long userId,
      long termsAgreementId,
      String noticeType,
      Instant expressedAt,
      Instant dueAt,
      int attemptCount,
      String name,
      String email,
      boolean agreedAtEvent,
      String consentPath,
      long latestAgreementId) {}

  public record ReconfirmCandidate(
      long userId, long termsAgreementId, Instant agreedAt, String termsVersion) {}

  private final JdbcClient jdbcClient;

  public ConsentNoticeBatchRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<DueNotice> claimDue(Instant now, int limit) {
    return jdbcClient
        .sql(
            """
            SELECT d.id, d.user_id, d.terms_agreement_id, d.notice_type, d.expressed_at, d.due_at, d.attempt_count,
                   u.name,
                   CASE WHEN u.account_status = 'ACTIVE' AND u.email_verified_at IS NOT NULL
                        THEN u.email END AS email,
                   a.agreed, a.consent_path,
                   COALESCE((SELECT max(t.id) FROM terms_agreement t
                              WHERE t.user_id = d.user_id AND t.terms_code = 'MARKETING'),
                            0) AS latest_id
              FROM consent_notice_delivery d
              JOIN app_user u ON u.id = d.user_id
              JOIN terms_agreement a ON a.id = d.terms_agreement_id
             WHERE d.status = 'PENDING' AND d.target_at <= :now
             ORDER BY d.target_at, d.id
             LIMIT :limit
             FOR UPDATE OF d SKIP LOCKED
            """)
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(
            (rs, i) ->
                new DueNotice(
                    rs.getLong("id"),
                    rs.getLong("user_id"),
                    rs.getLong("terms_agreement_id"),
                    rs.getString("notice_type"),
                    rs.getTimestamp("expressed_at").toInstant(),
                    rs.getTimestamp("due_at").toInstant(),
                    rs.getInt("attempt_count"),
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getBoolean("agreed"),
                    rs.getString("consent_path"),
                    rs.getLong("latest_id")))
        .list();
  }

  public void markSent(long id, int attempt, Instant now) {
    jdbcClient
        .sql(
            "UPDATE consent_notice_delivery SET status = 'SENT', attempt_count = :attempt,"
                + " sent_at = :now, failure_code = NULL WHERE id = :id")
        .param("attempt", attempt)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void markClosed(long id, String status, int attempt, String failureCode) {
    jdbcClient
        .sql(
            "UPDATE consent_notice_delivery SET status = :status, attempt_count = :attempt,"
                + " failure_code = :code WHERE id = :id")
        .param("status", status)
        .param("attempt", attempt)
        .param("code", failureCode)
        .param("id", id)
        .update();
  }

  public void reschedule(long id, int attempt, Instant nextAttemptAt, String failureCode) {
    jdbcClient
        .sql(
            "UPDATE consent_notice_delivery SET attempt_count = :attempt, target_at = :next,"
                + " failure_code = :code WHERE id = :id")
        .param("attempt", attempt)
        .param("next", Timestamp.from(nextAttemptAt))
        .param("code", failureCode)
        .param("id", id)
        .update();
  }

  /** 발송 기한까지 남은 시간이 경고 기준 이하인데 아직 보내지 못한 통지의 ID다. */
  public List<Long> atRiskIds(Instant dueThreshold) {
    return jdbcClient
        .sql(
            "SELECT id FROM consent_notice_delivery WHERE status = 'PENDING' AND due_at <="
                + " :threshold ORDER BY id")
        .param("threshold", Timestamp.from(dueThreshold))
        .query(Long.class)
        .list();
  }

  /** 최신 마케팅 동의가 동의 상태이고 시행 버전과 같은 활성 회원이다. */
  public List<ReconfirmCandidate> reconfirmCandidates(
      String inForceVersion, Instant agreedNoLaterThan) {
    return jdbcClient
        .sql(
            """
            SELECT a.user_id, a.id, a.recorded_at, a.terms_version
              FROM terms_agreement a
              JOIN app_user u ON u.id = a.user_id AND u.account_status = 'ACTIVE'
             WHERE a.terms_code = 'MARKETING' AND a.agreed
               AND a.terms_version = :version AND a.recorded_at <= :threshold
               AND a.id = (SELECT max(t.id) FROM terms_agreement t
                            WHERE t.user_id = a.user_id AND t.terms_code = 'MARKETING')
             ORDER BY a.id
            """)
        .param("version", inForceVersion)
        .param("threshold", Timestamp.from(agreedNoLaterThan))
        .query(
            (rs, i) ->
                new ReconfirmCandidate(
                    rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(), rs.getString(4)))
        .list();
  }

  public boolean insertReconfirm(
      ReconfirmCandidate candidate, int cycleNo, Instant targetAt, Instant dueAt, Instant now) {
    return !jdbcClient
        .sql(
            """
            INSERT INTO consent_notice_delivery (user_id, terms_agreement_id, notice_type,
              cycle_no, expressed_at, target_at, due_at, status, attempt_count, created_at)
            VALUES (:user, :agreement, 'MARKETING_RECONFIRM', :cycle, :expressed, :target,
              :due, 'PENDING', 0, :now)
            ON CONFLICT (terms_agreement_id, notice_type, cycle_no) DO NOTHING
            RETURNING id
            """)
        .param("user", candidate.userId())
        .param("agreement", candidate.termsAgreementId())
        .param("cycle", cycleNo)
        .param("expressed", Timestamp.from(candidate.agreedAt()))
        .param("target", Timestamp.from(targetAt))
        .param("due", Timestamp.from(dueAt))
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .list()
        .isEmpty();
  }
}
