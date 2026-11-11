package com.sairo.be.domain.support.entity;

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
@Table(name = "support_inquiry_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportInquiryMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "inquiry_id", nullable = false, updatable = false)
  private Long inquiryId;

  @Enumerated(EnumType.STRING)
  @Column(name = "author_type", nullable = false, length = 10, updatable = false)
  private AuthorType authorType;

  @Column(name = "author_user_id", updatable = false)
  private Long authorUserId;

  @Column(name = "author_operator_id", updatable = false)
  private Long authorOperatorId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private MessageVisibility visibility;

  @Column(nullable = false, columnDefinition = "text", updatable = false)
  private String content;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static SupportInquiryMessage requesterReply(
      Long inquiryId, Long userId, String content, Instant now) {
    SupportInquiryMessage message = base(inquiryId, content, MessageVisibility.PUBLIC, now);
    message.authorType = AuthorType.REQUESTER;
    message.authorUserId = userId;
    return message;
  }

  public static SupportInquiryMessage operatorReply(
      Long inquiryId, Long operatorId, String content, Instant now) {
    SupportInquiryMessage message = base(inquiryId, content, MessageVisibility.PUBLIC, now);
    message.authorType = AuthorType.OPERATOR;
    message.authorOperatorId = operatorId;
    return message;
  }

  public static SupportInquiryMessage internalNote(
      Long inquiryId, Long operatorId, String content, Instant now) {
    SupportInquiryMessage message = base(inquiryId, content, MessageVisibility.INTERNAL, now);
    message.authorType = AuthorType.OPERATOR;
    message.authorOperatorId = operatorId;
    return message;
  }

  private static SupportInquiryMessage base(
      Long inquiryId, String content, MessageVisibility visibility, Instant now) {
    SupportInquiryMessage message = new SupportInquiryMessage();
    message.inquiryId = inquiryId;
    message.content = content;
    message.visibility = visibility;
    message.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    return message;
  }
}
