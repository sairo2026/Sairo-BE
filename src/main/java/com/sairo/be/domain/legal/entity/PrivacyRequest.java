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
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "privacy_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrivacyRequest {

  public static final int LEGAL_DUE_DAYS = 10;
  public static final int INTERNAL_DUE_DAYS = 7;
  public static final String LEGAL_BASIS = "개인정보처리방침 제9조(정보주체의 권리와 행사 방법), 접수일부터 10일 안에 결과 통지";
  public static final String UNSPECIFIED_SCOPE = "범위 미지정(접수 문의 본문 참조)";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "requester_user_id", updatable = false)
  private Long requesterUserId;

  @Column(name = "support_inquiry_id", updatable = false)
  private Long supportInquiryId;

  @Column(name = "office_id")
  private Long officeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "request_type", nullable = false, length = 20, updatable = false)
  private PrivacyRequestType requestType;

  @Enumerated(EnumType.STRING)
  @Column(name = "identity_status", nullable = false, length = 12)
  private IdentityStatus identityStatus;

  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  @Column(name = "legal_basis", nullable = false, columnDefinition = "text")
  private String legalBasis;

  @Column(name = "legal_due_at", nullable = false)
  private Instant legalDueAt;

  @Column(name = "internal_due_at", nullable = false)
  private Instant internalDueAt;

  @Column(name = "request_scope", nullable = false, columnDefinition = "text")
  private String requestScope;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private PrivacyRequestStatus status;

  @Column(name = "decision_reason", columnDefinition = "text")
  private String decisionReason;

  @Column(name = "completion_evidence", columnDefinition = "text")
  private String completionEvidence;

  @Column(name = "assigned_operator_id")
  private Long assignedOperatorId;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "handler_type", nullable = false, length = 10)
  private PrivacyHandlerType handlerType;

  @Column(nullable = false)
  private long version = 1;

  public static PrivacyRequest receiveFromMember(
      Long requesterUserId,
      Long supportInquiryId,
      PrivacyRequestType requestType,
      String requestScope,
      Instant now) {
    Instant at = now.truncatedTo(ChronoUnit.MICROS);
    PrivacyRequest request = new PrivacyRequest();
    request.requesterUserId = requesterUserId;
    request.supportInquiryId = supportInquiryId;
    request.requestType = requestType;
    request.identityStatus = IdentityStatus.PENDING;
    request.receivedAt = at;
    request.legalBasis = LEGAL_BASIS;
    request.legalDueAt = at.plus(LEGAL_DUE_DAYS, ChronoUnit.DAYS);
    request.internalDueAt = at.plus(INTERNAL_DUE_DAYS, ChronoUnit.DAYS);
    request.requestScope = requestScope == null ? UNSPECIFIED_SCOPE : requestScope;
    request.status = PrivacyRequestStatus.RECEIVED;
    request.handlerType = PrivacyHandlerType.SAIRO;
    return request;
  }

  /**
   * 비회원 링크 접수를 운영자가 본인 확인한 뒤 권리 요청으로 바꾼 것이다. 요청자 회원 ID 없이 링크 발급 사무소가 처리 책임을 지고(OFFICE) 접수 시각은 비회원
   * 접수 시각을 쓴다.
   */
  public static PrivacyRequest receiveFromGuest(
      Long officeId,
      PrivacyRequestType requestType,
      String requestScope,
      String legalBasis,
      Instant receivedAt,
      Instant legalDueAt,
      Instant internalDueAt) {
    PrivacyRequest request = new PrivacyRequest();
    request.officeId = officeId;
    request.requestType = requestType;
    request.identityStatus = IdentityStatus.VERIFIED;
    request.receivedAt = receivedAt.truncatedTo(ChronoUnit.MICROS);
    request.legalBasis = legalBasis;
    request.legalDueAt = legalDueAt.truncatedTo(ChronoUnit.MICROS);
    request.internalDueAt = internalDueAt.truncatedTo(ChronoUnit.MICROS);
    request.requestScope = requestScope;
    request.status = PrivacyRequestStatus.RECEIVED;
    request.handlerType = PrivacyHandlerType.OFFICE;
    return request;
  }

  /** 운영자 처리 단계를 반영한다. 담당이 없으면 처음 처리한 운영자를 담당으로 둔다. */
  public void moveTo(PrivacyRequestStatus next, Long operatorId) {
    this.status = next;
    if (assignedOperatorId == null) {
      this.assignedOperatorId = operatorId;
    }
  }

  public void increaseVersion() {
    this.version += 1;
  }

  public void recordIdentity(IdentityStatus identity) {
    this.identityStatus = identity;
  }

  public void recordDecision(String reason) {
    this.decisionReason = reason;
  }

  public void complete(String evidence, Instant now) {
    this.completionEvidence = evidence;
    this.completedAt = now.truncatedTo(ChronoUnit.MICROS);
  }
}
