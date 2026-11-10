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

@Entity
@Table(name = "consent_notice_delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsentNoticeDelivery {

  public static final Duration NOTICE_DEADLINE = Duration.ofDays(14);
  private static final String MARKETING_RESULT = "MARKETING_RESULT";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "terms_agreement_id", nullable = false)
  private Long termsAgreementId;

  @Column(name = "notice_type", nullable = false, length = 20)
  private String noticeType;

  @Column(name = "cycle_no", nullable = false)
  private int cycleNo;

  @Column(name = "expressed_at", nullable = false)
  private Instant expressedAt;

  @Column(name = "target_at", nullable = false)
  private Instant targetAt;

  @Column(name = "due_at", nullable = false)
  private Instant dueAt;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static ConsentNoticeDelivery resultNotice(
      Long userId, Long termsAgreementId, Instant expressedAt, boolean hasVerifiedEmail) {
    ConsentNoticeDelivery delivery = new ConsentNoticeDelivery();
    delivery.userId = userId;
    delivery.termsAgreementId = termsAgreementId;
    delivery.noticeType = MARKETING_RESULT;
    delivery.cycleNo = 1;
    delivery.expressedAt = expressedAt;
    delivery.targetAt = expressedAt;
    delivery.dueAt = expressedAt.plus(NOTICE_DEADLINE);
    delivery.status = hasVerifiedEmail ? "PENDING" : "SKIPPED_NO_EMAIL";
    delivery.attemptCount = 0;
    delivery.createdAt = expressedAt;
    return delivery;
  }
}
