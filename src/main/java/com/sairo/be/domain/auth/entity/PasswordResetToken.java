package com.sairo.be.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "password_reset_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {

  public static final Duration VALIDITY = Duration.ofMinutes(30);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "superseded_at")
  private Instant supersededAt;

  public static PasswordResetToken issue(Long userId, String tokenHash, Instant issuedAt) {
    PasswordResetToken token = new PasswordResetToken();
    token.userId = userId;
    token.tokenHash = tokenHash;
    token.issuedAt = issuedAt;
    token.expiresAt = issuedAt.plus(VALIDITY);
    return token;
  }

  public boolean isUsable() {
    return consumedAt == null && supersededAt == null;
  }

  public boolean isExpiredAt(Instant now) {
    return !expiresAt.isAfter(now);
  }
}
