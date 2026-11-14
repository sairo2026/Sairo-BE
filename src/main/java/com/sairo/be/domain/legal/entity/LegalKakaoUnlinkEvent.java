package com.sairo.be.domain.legal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "legal_kakao_unlink_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LegalKakaoUnlinkEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private KakaoUnlinkDirection direction;

  @Column(name = "user_id")
  private Long userId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "kakao_key_hmac", length = 64)
  private String kakaoKeyHmac;

  @Column(name = "hmac_key_version")
  private Short hmacKeyVersion;

  @Column(name = "target_key_encrypted", columnDefinition = "text")
  private String targetKeyEncrypted;

  @Column(name = "referrer_type", length = 40)
  private String referrerType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private KakaoUnlinkStatus status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "last_error_code", length = 40)
  private String lastErrorCode;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "key_wiped_at")
  private Instant keyWipedAt;

  public static LegalKakaoUnlinkEvent outbound(
      Long userId,
      String kakaoKeyHmac,
      short hmacKeyVersion,
      String targetKeyEncrypted,
      Instant now) {
    LegalKakaoUnlinkEvent event = new LegalKakaoUnlinkEvent();
    event.direction = KakaoUnlinkDirection.OUTBOUND_CALL;
    event.userId = userId;
    event.kakaoKeyHmac = kakaoKeyHmac;
    event.hmacKeyVersion = hmacKeyVersion;
    event.targetKeyEncrypted = targetKeyEncrypted;
    event.status = KakaoUnlinkStatus.PENDING;
    event.attemptCount = 0;
    event.nextAttemptAt = now;
    event.occurredAt = now;
    return event;
  }

  public static LegalKakaoUnlinkEvent inbound(
      Long userId,
      String kakaoKeyHmac,
      short hmacKeyVersion,
      String referrerType,
      KakaoUnlinkStatus status,
      Instant now) {
    LegalKakaoUnlinkEvent event = new LegalKakaoUnlinkEvent();
    event.direction = KakaoUnlinkDirection.INBOUND_WEBHOOK;
    event.userId = userId;
    event.kakaoKeyHmac = kakaoKeyHmac;
    event.hmacKeyVersion = hmacKeyVersion;
    event.referrerType = referrerType;
    event.status = status;
    event.attemptCount = 0;
    event.occurredAt = now;
    event.completedAt = now;
    return event;
  }

  public void markSucceeded(Instant now) {
    this.attemptCount += 1;
    this.status = KakaoUnlinkStatus.SUCCEEDED;
    this.nextAttemptAt = null;
    this.lastErrorCode = null;
    this.completedAt = now;
    wipeKey(now);
  }

  public void markFailed(Instant now, String errorCode, Instant nextAttempt) {
    this.attemptCount += 1;
    this.status = KakaoUnlinkStatus.FAILED_RETRYING;
    this.lastErrorCode = errorCode;
    this.nextAttemptAt = nextAttempt;
  }

  public void abandon(Instant now) {
    this.status = KakaoUnlinkStatus.ABANDONED;
    this.nextAttemptAt = null;
    this.completedAt = now;
    wipeKey(now);
  }

  private void wipeKey(Instant now) {
    this.targetKeyEncrypted = null;
    this.keyWipedAt = now;
  }
}
