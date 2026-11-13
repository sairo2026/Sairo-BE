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

  public record Listing(
      long id,
      String inviteeName,
      String inviteeEmail,
      InvitationStatus status,
      Instant expiresAt,
      long invitedByOperatorId,
      String invitedByName,
      Long acceptedOperatorId,
      Instant acceptedAt,
      Instant cancelledAt,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  private static final String LISTING_COLUMNS =
      "i.id, i.invitee_name, i.invitee_email, i.status, i.expires_at, i.invited_by_operator_id,"
          + " o.name AS invited_by_name, i.accepted_operator_id, i.accepted_at, i.cancelled_at,"
          + " i.created_at, i.updated_at, i.version";

  /** 만료 시각이 지난 대기 초대는 EXPIRED로 본다. */
  private static final String EFFECTIVE_STATUS =
      "CASE WHEN i.status = 'PENDING' AND i.expires_at <= :now THEN 'EXPIRED' ELSE i.status END";

  public long insert(
      String inviteeName,
      String inviteeEmail,
      String tokenHash,
      long invitedBy,
      Instant now,
      Instant expiresAt) {
    return jdbcClient
        .sql(
            "INSERT INTO operator_invitation (invitee_name, invitee_email, token_hash, status,"
                + " invited_by_operator_id, expires_at, created_at, updated_at) VALUES (:name,"
                + " :email, :hash, 'PENDING', :invitedBy, :expiresAt, :now, :now) RETURNING id")
        .param("name", inviteeName)
        .param("email", inviteeEmail)
        .param("hash", tokenHash)
        .param("invitedBy", invitedBy)
        .param("expiresAt", Timestamp.from(expiresAt))
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public void insertRole(long invitationId, OperatorRole role) {
    jdbcClient
        .sql("INSERT INTO operator_invitation_role (invitation_id, role) VALUES (:id, :role)")
        .param("id", invitationId)
        .param("role", role.name())
        .update();
  }

  public boolean existsLivePendingInvitedBy(long inviterId, Instant now) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM operator_invitation WHERE invited_by_operator_id = :inviter"
                    + " AND status = 'PENDING' AND expires_at > :now")
            .param("inviter", inviterId)
            .param("now", Timestamp.from(now))
            .query(Long.class)
            .single()
        > 0;
  }

  public List<Long> lockPendingByEmail(String email) {
    return jdbcClient
        .sql(
            "SELECT id FROM operator_invitation WHERE invitee_email = :email"
                + " AND status = 'PENDING' FOR UPDATE")
        .param("email", email)
        .query(Long.class)
        .list();
  }

  public void markExpired(long id, Instant now) {
    jdbcClient
        .sql(
            "UPDATE operator_invitation SET status = 'EXPIRED', updated_at = :now,"
                + " version = version + 1 WHERE id = :id AND status = 'PENDING'")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void markCancelled(long id, long operatorId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE operator_invitation SET status = 'CANCELLED', cancelled_by_operator_id ="
                + " :operator, cancelled_at = :now, updated_at = :now, version = version + 1"
                + " WHERE id = :id")
        .param("operator", operatorId)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public Optional<Listing> listing(long id, Instant now) {
    return jdbcClient
        .sql(
            "SELECT "
                + LISTING_COLUMNS.replace("i.status,", EFFECTIVE_STATUS + " AS status,")
                + " FROM operator_invitation i JOIN operator_account o"
                + " ON o.id = i.invited_by_operator_id WHERE i.id = :id")
        .param("id", id)
        .param("now", Timestamp.from(now))
        .query(this::mapListing)
        .optional();
  }

  public List<Listing> list(InvitationStatus status, Instant now, int limit, long offset) {
    return jdbcClient
        .sql(
            "SELECT "
                + LISTING_COLUMNS.replace("i.status,", EFFECTIVE_STATUS + " AS status,")
                + " FROM operator_invitation i JOIN operator_account o"
                + " ON o.id = i.invited_by_operator_id WHERE (CAST(:status AS VARCHAR) IS NULL OR "
                + EFFECTIVE_STATUS
                + " = :status) ORDER BY i.created_at DESC, i.id DESC LIMIT :limit OFFSET :offset")
        .param("status", status == null ? null : status.name())
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .param("offset", offset)
        .query(this::mapListing)
        .list();
  }

  public long count(InvitationStatus status, Instant now) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM operator_invitation i WHERE (CAST(:status AS VARCHAR) IS NULL"
                + " OR "
                + EFFECTIVE_STATUS
                + " = :status)")
        .param("status", status == null ? null : status.name())
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  private Listing mapListing(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
    Timestamp acceptedAt = rs.getTimestamp("accepted_at");
    Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
    return new Listing(
        rs.getLong("id"),
        rs.getString("invitee_name"),
        rs.getString("invitee_email"),
        InvitationStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getLong("invited_by_operator_id"),
        rs.getString("invited_by_name"),
        rs.getObject("accepted_operator_id", Long.class),
        acceptedAt == null ? null : acceptedAt.toInstant(),
        cancelledAt == null ? null : cancelledAt.toInstant(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getLong("version"));
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
