package com.sairo.be.domain.ops.repository;

import com.sairo.be.domain.ops.entity.InvitationStatus;
import com.sairo.be.global.security.OperatorRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class OperatorInvitationRepository {

  public record Row(
      long id,
      String inviteeName,
      InvitationStatus status,
      Instant expiresAt,
      String kakaoProviderKey,
      String pendingTotpSecretEncrypted,
      Instant enrollmentExpiresAt) {}

  private static final String COLUMNS =
      "id, invitee_name, status, expires_at, kakao_provider_key, pending_totp_secret_encrypted,"
          + " enrollment_expires_at";

  private final JdbcClient jdbcClient;

  public OperatorInvitationRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<Row> findByTokenHash(String tokenHash) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM operator_invitation WHERE token_hash = :hash")
        .param("hash", tokenHash)
        .query(this::map)
        .optional();
  }

  public Optional<Row> lockById(long id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM operator_invitation WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  public Optional<Row> findById(long id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM operator_invitation WHERE id = :id")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  public List<OperatorRole> roles(long invitationId) {
    return jdbcClient
        .sql("SELECT role FROM operator_invitation_role WHERE invitation_id = :id ORDER BY id")
        .param("id", invitationId)
        .query((rs, i) -> OperatorRole.valueOf(rs.getString("role")))
        .list();
  }

  public void bindKakao(
      long id, String kakaoProviderKey, Instant enrollmentExpiresAt, Instant now) {
    jdbcClient
        .sql(
            "UPDATE operator_invitation SET kakao_provider_key = :key,"
                + " enrollment_expires_at = :until, pending_totp_secret_encrypted = NULL,"
                + " updated_at = :now, version = version + 1 WHERE id = :id")
        .param("key", kakaoProviderKey)
        .param("until", Timestamp.from(enrollmentExpiresAt))
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void savePendingSecret(long id, String encryptedSecret, Instant now) {
    jdbcClient
        .sql(
            "UPDATE operator_invitation SET pending_totp_secret_encrypted = :secret,"
                + " updated_at = :now, version = version + 1 WHERE id = :id")
        .param("secret", encryptedSecret)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void markAccepted(long id, long operatorId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE operator_invitation SET status = 'ACCEPTED', accepted_operator_id = :operator,"
                + " accepted_at = :now, pending_totp_secret_encrypted = NULL, updated_at = :now,"
                + " version = version + 1 WHERE id = :id")
        .param("operator", operatorId)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  private Row map(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
    Timestamp enrollment = rs.getTimestamp("enrollment_expires_at");
    return new Row(
        rs.getLong("id"),
        rs.getString("invitee_name"),
        InvitationStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getString("kakao_provider_key"),
        rs.getString("pending_totp_secret_encrypted"),
        enrollment == null ? null : enrollment.toInstant());
  }
}
