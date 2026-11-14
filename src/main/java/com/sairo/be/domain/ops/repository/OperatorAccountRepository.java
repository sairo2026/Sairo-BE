package com.sairo.be.domain.ops.repository;

import com.sairo.be.domain.ops.entity.OperatorStatus;
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
public class OperatorAccountRepository {

  public record Row(
      long id,
      String kakaoProviderKey,
      String name,
      OperatorStatus status,
      String totpSecretEncrypted,
      int mfaFailedCount,
      Instant mfaLockedUntil) {}

  private static final String COLUMNS =
      "id, kakao_provider_key, name, status, totp_secret_encrypted, mfa_failed_count,"
          + " mfa_locked_until";

  private final JdbcClient jdbcClient;

  public OperatorAccountRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<Row> findByKakaoProviderKey(String key) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM operator_account WHERE kakao_provider_key = :key")
        .param("key", key)
        .query(this::map)
        .optional();
  }

  public Optional<Row> findById(long id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM operator_account WHERE id = :id")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  public Optional<Row> lockById(long id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM operator_account WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  public boolean existsByKakaoProviderKey(String key) {
    return jdbcClient
            .sql("SELECT count(*) FROM operator_account WHERE kakao_provider_key = :key")
            .param("key", key)
            .query(Long.class)
            .single()
        > 0;
  }

  public List<OperatorRole> roles(long operatorId) {
    return jdbcClient
        .sql(
            "SELECT role FROM operator_role_assignment WHERE operator_account_id = :id"
                + " ORDER BY id")
        .param("id", operatorId)
        .query((rs, i) -> OperatorRole.valueOf(rs.getString("role")))
        .list();
  }

  public long insert(String kakaoProviderKey, String name, String totpSecretEncrypted) {
    return jdbcClient
        .sql(
            "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
                + " totp_secret_encrypted) VALUES (:key, :name, 'ACTIVE', true, :secret)"
                + " RETURNING id")
        .param("key", kakaoProviderKey)
        .param("name", name)
        .param("secret", totpSecretEncrypted)
        .query(Long.class)
        .single();
  }

  public void insertRole(long operatorId, OperatorRole role, Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO operator_role_assignment (operator_account_id, role, assigned_at)"
                + " VALUES (:id, :role, :now)")
        .param("id", operatorId)
        .param("role", role.name())
        .param("now", Timestamp.from(now))
        .update();
  }

  public void updateMfaState(long operatorId, int failedCount, Instant lockedUntil) {
    jdbcClient
        .sql(
            "UPDATE operator_account SET mfa_failed_count = :count, mfa_locked_until = :until"
                + " WHERE id = :id")
        .param("count", failedCount)
        .param("until", lockedUntil == null ? null : Timestamp.from(lockedUntil))
        .param("id", operatorId)
        .update();
  }

  public record Option(long id, String name, List<String> roles) {}

  public List<Option> activeOptions() {
    return jdbcClient
        .sql(
            "SELECT a.id, a.name, COALESCE(string_agg(r.role, ',' ORDER BY r.id), '') AS roles"
                + " FROM operator_account a LEFT JOIN operator_role_assignment r"
                + " ON r.operator_account_id = a.id WHERE a.status = 'ACTIVE'"
                + " GROUP BY a.id, a.name ORDER BY a.name, a.id")
        .query(
            (rs, i) -> {
              String roles = rs.getString("roles");
              return new Option(
                  rs.getLong("id"),
                  rs.getString("name"),
                  roles.isEmpty() ? List.of() : List.of(roles.split(",")));
            })
        .list();
  }

  public record Summary(long id, String name, OperatorStatus status, Instant createdAt) {}

  public List<Summary> list(int limit, long offset) {
    return jdbcClient
        .sql(
            "SELECT id, name, status, created_at FROM operator_account ORDER BY id"
                + " LIMIT :limit OFFSET :offset")
        .param("limit", limit)
        .param("offset", offset)
        .query(this::mapSummary)
        .list();
  }

  public long countActive() {
    return jdbcClient
        .sql("SELECT count(*) FROM operator_account WHERE status = 'ACTIVE'")
        .query(Long.class)
        .single();
  }

  public long ensureSystemAccount(String kakaoProviderKey, String name) {
    return jdbcClient
        .sql(
            "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
                + " totp_secret_encrypted) VALUES (:key, :name, 'DISABLED', true, 'SYSTEM_ACCOUNT')"
                + " ON CONFLICT (kakao_provider_key) DO UPDATE SET name = operator_account.name"
                + " RETURNING id")
        .param("key", kakaoProviderKey)
        .param("name", name)
        .query(Long.class)
        .single();
  }

  public long count() {
    return jdbcClient.sql("SELECT count(*) FROM operator_account").query(Long.class).single();
  }

  public Optional<Summary> summary(long id) {
    return jdbcClient
        .sql("SELECT id, name, status, created_at FROM operator_account WHERE id = :id")
        .param("id", id)
        .query(this::mapSummary)
        .optional();
  }

  public void updateStatus(long id, OperatorStatus status) {
    jdbcClient
        .sql("UPDATE operator_account SET status = :status WHERE id = :id")
        .param("status", status.name())
        .param("id", id)
        .update();
  }

  public void deleteRole(long operatorId, OperatorRole role) {
    jdbcClient
        .sql(
            "DELETE FROM operator_role_assignment WHERE operator_account_id = :id"
                + " AND role = :role")
        .param("id", operatorId)
        .param("role", role.name())
        .update();
  }

  /** 대상을 뺀 활성 보안관리자 수다. */
  public long countOtherActiveSecurityAdmins(long excludedOperatorId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM operator_account a JOIN operator_role_assignment r"
                + " ON r.operator_account_id = a.id WHERE r.role = 'SECURITY_ADMIN'"
                + " AND a.status = 'ACTIVE' AND a.id <> :id")
        .param("id", excludedOperatorId)
        .query(Long.class)
        .single();
  }

  public boolean isActiveWithRole(long operatorId, OperatorRole role) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM operator_account a JOIN operator_role_assignment r"
                    + " ON r.operator_account_id = a.id WHERE a.id = :id AND a.status = 'ACTIVE'"
                    + " AND r.role = :role")
            .param("id", operatorId)
            .param("role", role.name())
            .query(Long.class)
            .single()
        > 0;
  }

  public boolean isActive(long operatorId) {
    return jdbcClient
            .sql("SELECT count(*) FROM operator_account WHERE id = :id AND status = 'ACTIVE'")
            .param("id", operatorId)
            .query(Long.class)
            .single()
        > 0;
  }

  private Summary mapSummary(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
    return new Summary(
        rs.getLong("id"),
        rs.getString("name"),
        OperatorStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("created_at").toInstant());
  }

  private Row map(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
    Timestamp locked = rs.getTimestamp("mfa_locked_until");
    return new Row(
        rs.getLong("id"),
        rs.getString("kakao_provider_key"),
        rs.getString("name"),
        OperatorStatus.valueOf(rs.getString("status")),
        rs.getString("totp_secret_encrypted"),
        rs.getInt("mfa_failed_count"),
        locked == null ? null : locked.toInstant());
  }
}
