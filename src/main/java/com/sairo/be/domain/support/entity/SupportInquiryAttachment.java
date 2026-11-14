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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "support_inquiry_attachment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportInquiryAttachment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "inquiry_id", nullable = false, updatable = false)
  private Long inquiryId;

  @Column(name = "message_id", updatable = false)
  private Long messageId;

  @Column(name = "object_key", nullable = false, length = 500, updatable = false)
  private String objectKey;

  @Column(name = "file_name", nullable = false, length = 255, updatable = false)
  private String fileName;

  @Column(name = "media_type", nullable = false, length = 100, updatable = false)
  private String mediaType;

  @Column(name = "size_bytes", nullable = false, updatable = false)
  private long sizeBytes;

  @Column(name = "uploaded_by_user_id", updatable = false)
  private Long uploadedByUserId;

  @Column(name = "uploaded_by_operator_id", updatable = false)
  private Long uploadedByOperatorId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scan_status", nullable = false, length = 10)
  private ScanStatus scanStatus;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "object_deleted_at")
  private Instant objectDeletedAt;

  public static SupportInquiryAttachment uploadedByRequester(
      Long inquiryId,
      Long messageId,
      Long userId,
      String objectKey,
      String fileName,
      String mediaType,
      long sizeBytes,
      Instant now) {
    SupportInquiryAttachment attachment = pending(inquiryId, messageId, objectKey, now);
    attachment.uploadedByUserId = userId;
    attachment.fileName = fileName;
    attachment.mediaType = mediaType;
    attachment.sizeBytes = sizeBytes;
    return attachment;
  }

  public static SupportInquiryAttachment uploadedByOperator(
      Long inquiryId,
      Long messageId,
      Long operatorId,
      String objectKey,
      String fileName,
      String mediaType,
      long sizeBytes,
      Instant now) {
    SupportInquiryAttachment attachment = pending(inquiryId, messageId, objectKey, now);
    attachment.uploadedByOperatorId = operatorId;
    attachment.fileName = fileName;
    attachment.mediaType = mediaType;
    attachment.sizeBytes = sizeBytes;
    return attachment;
  }

  private static SupportInquiryAttachment pending(
      Long inquiryId, Long messageId, String objectKey, Instant now) {
    SupportInquiryAttachment attachment = new SupportInquiryAttachment();
    attachment.inquiryId = inquiryId;
    attachment.messageId = messageId;
    attachment.objectKey = objectKey;
    attachment.scanStatus = ScanStatus.PENDING;
    attachment.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    return attachment;
  }

  public void markClean() {
    requirePending();
    scanStatus = ScanStatus.CLEAN;
  }

  public void markRejected() {
    requirePending();
    scanStatus = ScanStatus.REJECTED;
  }

  public void markObjectDeleted(Instant now) {
    if (scanStatus != ScanStatus.REJECTED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (objectDeletedAt == null) {
      objectDeletedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
  }

  private void requirePending() {
    if (scanStatus != ScanStatus.PENDING) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }
}
