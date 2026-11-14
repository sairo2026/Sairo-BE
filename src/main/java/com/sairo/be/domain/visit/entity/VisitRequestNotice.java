package com.sairo.be.domain.visit.entity;

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

@Entity
@Table(name = "visit_request_notice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitRequestNotice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "request_id", nullable = false, updatable = false)
  private Long requestId;

  @Column(name = "recipient_membership_id", nullable = false, updatable = false)
  private Long recipientMembershipId;

  @Enumerated(EnumType.STRING)
  @Column(name = "notice_type", nullable = false, length = 30, updatable = false)
  private RequestNoticeType noticeType;

  @Column(length = 20, updatable = false)
  private String channel;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "read_at")
  private Instant readAt;

  public static VisitRequestNotice of(
      Long requestId, Long recipientMembershipId, RequestNoticeType type, Instant at) {
    VisitRequestNotice notice = new VisitRequestNotice();
    notice.requestId = requestId;
    notice.recipientMembershipId = recipientMembershipId;
    notice.noticeType = type;
    notice.createdAt = at;
    return notice;
  }

  public void markRead(Instant at) {
    if (readAt == null) {
      readAt = at;
    }
  }
}
