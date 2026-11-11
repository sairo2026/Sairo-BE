package com.sairo.be.domain.support.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.support.dto.response.SupportInquiryAttachmentResponse;
import com.sairo.be.domain.support.entity.ScanStatus;
import com.sairo.be.domain.support.entity.SupportInquiry;
import com.sairo.be.domain.support.entity.SupportInquiryAttachment;
import com.sairo.be.domain.support.mapper.SupportInquiryMapper;
import com.sairo.be.domain.support.repository.SupportInquiryAttachmentRepository;
import com.sairo.be.domain.support.repository.SupportInquiryMessageRepository;
import com.sairo.be.domain.support.repository.SupportInquiryRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.StaffPrincipal;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Profile("!migrate")
@Service
public class SupportAttachmentService {

  public record Download(String fileName, String mediaType, byte[] content) {}

  private final SupportInquiryRepository inquiryRepository;
  private final SupportInquiryMessageRepository messageRepository;
  private final SupportInquiryAttachmentRepository attachmentRepository;
  private final ObjectStorage objectStorage;
  private final AttachmentScanDispatcher scanDispatcher;
  private final Clock clock;

  public SupportAttachmentService(
      SupportInquiryRepository inquiryRepository,
      SupportInquiryMessageRepository messageRepository,
      SupportInquiryAttachmentRepository attachmentRepository,
      ObjectStorage objectStorage,
      AttachmentScanDispatcher scanDispatcher,
      Clock clock) {
    this.inquiryRepository = inquiryRepository;
    this.messageRepository = messageRepository;
    this.attachmentRepository = attachmentRepository;
    this.objectStorage = objectStorage;
    this.scanDispatcher = scanDispatcher;
    this.clock = clock;
  }

  @Transactional
  public SupportInquiryAttachmentResponse upload(
      StaffPrincipal principal, Long inquiryId, MultipartFile file, String messageIdInput) {
    SupportInquiry inquiry =
        inquiryRepository
            .findForUpdateByRequester(inquiryId, principal.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (file == null || file.isEmpty()) {
      throw CustomerInput.invalid("file", FieldErrorCode.REQUIRED, "파일이 필요합니다.");
    }
    Long messageId = requesterMessage(inquiryId, principal.userId(), messageIdInput);
    String fileName = AttachmentRules.fileName(file.getOriginalFilename());
    String mediaType = AttachmentRules.mediaTypeFor(fileName, file.getContentType());
    if (file.getSize() > AttachmentRules.MAX_BYTES) {
      throw new BusinessException(ErrorCode.ATTACHMENT_TOO_LARGE);
    }
    if (attachmentRepository.countByInquiryIdAndUploadedByUserIdIsNotNull(inquiryId)
        >= AttachmentRules.MAX_PER_INQUIRY) {
      throw new BusinessException(ErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
    }
    byte[] content = read(file);
    AttachmentRules.requireSignatureMatches(mediaType, content);

    Instant now = clock.instant();
    String key =
        "support-inquiries/"
            + inquiryId
            + "/"
            + UUID.randomUUID()
            + AttachmentRules.extension(fileName);
    objectStorage.put(key, content, mediaType);
    try {
      SupportInquiryAttachment saved =
          attachmentRepository.saveAndFlush(
              SupportInquiryAttachment.uploadedByRequester(
                  inquiryId,
                  messageId,
                  principal.userId(),
                  key,
                  fileName,
                  mediaType,
                  content.length,
                  now));
      inquiry.requesterAdded(now);
      inquiryRepository.saveAndFlush(inquiry);
      scanDispatcher.dispatch(saved.getId());
      return SupportInquiryMapper.toAttachment(saved);
    } catch (RuntimeException e) {
      objectStorage.delete(key);
      throw e;
    }
  }

  @Transactional(readOnly = true)
  public Download download(StaffPrincipal principal, Long inquiryId, Long attachmentId) {
    inquiryRepository
        .findByIdAndRequesterUserId(inquiryId, principal.userId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    SupportInquiryAttachment attachment =
        attachmentRepository
            .findVisibleToRequesterById(inquiryId, attachmentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (attachment.getScanStatus() != ScanStatus.CLEAN) {
      throw new BusinessException(ErrorCode.ATTACHMENT_NOT_READY);
    }
    byte[] content =
        objectStorage
            .find(attachment.getObjectKey())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return new Download(attachment.getFileName(), attachment.getMediaType(), content);
  }

  private Long requesterMessage(Long inquiryId, Long userId, String messageIdInput) {
    if (messageIdInput == null || messageIdInput.isBlank()) {
      return null;
    }
    long messageId;
    try {
      messageId = Long.parseLong(messageIdInput.strip());
    } catch (NumberFormatException e) {
      throw CustomerInput.invalid("messageId", FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
    }
    return messageRepository
        .findRequesterMessage(messageId, inquiryId, userId)
        .map(message -> message.getId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private static byte[] read(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw CustomerInput.invalid("file", FieldErrorCode.INVALID_FORMAT, "파일을 읽을 수 없습니다.");
    }
  }
}
