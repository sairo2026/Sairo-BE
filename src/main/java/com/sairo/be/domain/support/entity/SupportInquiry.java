package com.sairo.be.domain.support.entity;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "support_inquiry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportInquiry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", updatable = false)
  private Long officeId;

  @Column(name = "requester_user_id", nullable = false, updatable = false)
  private Long requesterUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20, updatable = false)
  private InquiryCategory category;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private InquiryStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private InquiryPriority priority;

  @Column(name = "assigned_operator_id")
  private Long assignedOperatorId;

  @Column(name = "request_scope", columnDefinition = "text", updatable = false)
  private String requestScope;

  @Column(nullable = false, columnDefinition = "text", updatable = false)
  private String content;

  @Column(columnDefinition = "text")
  private String answer;

  @Column(name = "response_due_at", nullable = false)
  private Instant responseDueAt;

  @Column(name = "answered_at")
  private Instant answeredAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static SupportInquiry open(
      Long requesterUserId,
      Long officeId,
      InquiryCategory category,
      String content,
      String requestScope,
      Instant responseDueAt,
      Instant now) {
    Instant at = now.truncatedTo(ChronoUnit.MICROS);
    SupportInquiry inquiry = new SupportInquiry();
    inquiry.requesterUserId = requesterUserId;
    inquiry.officeId = officeId;
    inquiry.category = category;
    inquiry.content = content;
    inquiry.requestScope = requestScope;
    inquiry.status = InquiryStatus.OPEN;
    inquiry.priority = InquiryPriority.NORMAL;
    inquiry.responseDueAt = responseDueAt.truncatedTo(ChronoUnit.MICROS);
    inquiry.createdAt = at;
    inquiry.updatedAt = at;
    return inquiry;
  }

  public void requesterAdded(Instant now) {
    switch (status) {
      case CLOSED -> {
        status = InquiryStatus.OPEN;
        closedAt = null;
      }
      case WAITING_FOR_CUSTOMER, ANSWERED ->
          status = assignedOperatorId == null ? InquiryStatus.OPEN : InquiryStatus.ASSIGNED;
      case OPEN, ASSIGNED -> {}
    }
    touch(now);
  }

  public void assignTo(Long operatorId, Instant now) {
    if (status == InquiryStatus.CLOSED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    assignedOperatorId = operatorId;
    if (status == InquiryStatus.OPEN) {
      status = InquiryStatus.ASSIGNED;
    }
    touch(now);
  }

  public void answered(
      String reply, boolean needsCustomerReply, boolean closeAfterReply, Instant now) {
    if (needsCustomerReply && closeAfterReply) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
    if (status == InquiryStatus.CLOSED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant at = now.truncatedTo(ChronoUnit.MICROS);
    answer = reply;
    answeredAt = at;
    if (closeAfterReply) {
      status = InquiryStatus.CLOSED;
      closedAt = at;
    } else if (needsCustomerReply) {
      status = InquiryStatus.WAITING_FOR_CUSTOMER;
    } else {
      status = InquiryStatus.ANSWERED;
    }
    touch(now);
  }

  public void changePriority(InquiryPriority next, Instant recalculatedDueAt, Instant now) {
    if (status == InquiryStatus.CLOSED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    priority = next;
    if (recalculatedDueAt != null) {
      responseDueAt = recalculatedDueAt.truncatedTo(ChronoUnit.MICROS);
    }
    touch(now);
  }

  public void requireOpenForOperator() {
    if (status == InquiryStatus.CLOSED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  public void touch(Instant now) {
    updatedAt = now.truncatedTo(ChronoUnit.MICROS);
  }
}
