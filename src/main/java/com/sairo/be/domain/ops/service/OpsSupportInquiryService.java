package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.SupportInquiryOperatorReplyRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.SupportInquiryPriorityRequest;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportAttachment;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryAnswerResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryAssignResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryAttachmentResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryItem;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryNoteResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryPriorityResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportMessage;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsSupportQueryRepository;
import com.sairo.be.domain.ops.repository.OpsSupportQueryRepository.InquiryFilter;
import com.sairo.be.domain.ops.repository.OpsSupportQueryRepository.UserName;
import com.sairo.be.domain.support.entity.InquiryPriority;
import com.sairo.be.domain.support.entity.MessageVisibility;
import com.sairo.be.domain.support.entity.ScanStatus;
import com.sairo.be.domain.support.entity.SupportInquiry;
import com.sairo.be.domain.support.entity.SupportInquiryAttachment;
import com.sairo.be.domain.support.entity.SupportInquiryMessage;
import com.sairo.be.domain.support.repository.SupportDirectoryRepository;
import com.sairo.be.domain.support.repository.SupportInquiryAttachmentRepository;
import com.sairo.be.domain.support.repository.SupportInquiryMessageRepository;
import com.sairo.be.domain.support.repository.SupportInquiryRepository;
import com.sairo.be.domain.support.service.AttachmentRules;
import com.sairo.be.domain.support.service.AttachmentScanDispatcher;
import com.sairo.be.domain.support.service.ResponseDueCalculator;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.NotificationPublisher;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 운영 콘솔의 지원 문의 처리다(OPS-05). 고객 쪽 문의 엔티티와 첨부 규칙을 그대로 쓴다. */
@Profile("!migrate")
@Service
public class OpsSupportInquiryService {

  static final int OPERATOR_ATTACHMENT_MAX = 5;
  static final Set<String> CATEGORIES =
      Set.of("SIGNUP", "AUTH", "BUSINESS", "BILLING", "MIGRATION", "PRIVACY", "BUG", "ENTERPRISE");
  static final Set<String> STATUSES =
      Set.of("OPEN", "ASSIGNED", "WAITING_FOR_CUSTOMER", "ANSWERED", "CLOSED");
  static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH");
  private static final String TARGET = "SUPPORT_INQUIRY";

  public record Download(String fileName, String mediaType, byte[] content) {}

  private final SupportInquiryRepository inquiries;
  private final SupportInquiryMessageRepository messages;
  private final SupportInquiryAttachmentRepository attachments;
  private final SupportDirectoryRepository directory;
  private final OpsSupportQueryRepository queries;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final ObjectStorage objectStorage;
  private final AttachmentScanDispatcher scanDispatcher;
  private final NotificationPublisher notifications;
  private final Clock clock;

  public OpsSupportInquiryService(
      SupportInquiryRepository inquiries,
      SupportInquiryMessageRepository messages,
      SupportInquiryAttachmentRepository attachments,
      SupportDirectoryRepository directory,
      OpsSupportQueryRepository queries,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      ObjectStorage objectStorage,
      AttachmentScanDispatcher scanDispatcher,
      NotificationPublisher notifications,
      Clock clock) {
    this.inquiries = inquiries;
    this.messages = messages;
    this.attachments = attachments;
    this.directory = directory;
    this.queries = queries;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.objectStorage = objectStorage;
    this.scanDispatcher = scanDispatcher;
    this.notifications = notifications;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<OpsSupportInquiryItem> list(
      String category,
      String status,
      String priority,
      Long assignedOperatorId,
      Boolean unassigned,
      Boolean overdue,
      Long officeId,
      PageQuery page) {
    InquiryFilter filter =
        new InquiryFilter(
            OpsQueryParams.oneOf("category", category, CATEGORIES),
            OpsQueryParams.oneOf("status", status, STATUSES),
            OpsQueryParams.oneOf("priority", priority, PRIORITIES),
            OpsQueryParams.positiveId("assignedOperatorId", assignedOperatorId),
            Boolean.TRUE.equals(unassigned),
            Boolean.TRUE.equals(overdue),
            OpsQueryParams.positiveId("officeId", officeId),
            clock.instant());
    if (filter.unassigned() && filter.assignedOperatorId() != null) {
      throw OpsQueryParams.invalid(
          "unassigned", FieldErrorCode.NOT_ALLOWED, "unassigned와 assignedOperatorId는 함께 쓸 수 없습니다.");
    }
    List<OpsSupportInquiryItem> items =
        queries.inquiries(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(
                row ->
                    new OpsSupportInquiryItem(
                        row.id(),
                        row.officeId(),
                        row.officeName(),
                        row.requesterUserId(),
                        OpsDisplayNames.member(row.requesterName(), row.requesterWithdrawn()),
                        row.category(),
                        row.status(),
                        row.priority(),
                        row.assignedOperatorId(),
                        row.assignedOperatorName(),
                        toDisplayOffset(row.responseDueAt()),
                        toDisplayOffset(row.answeredAt()),
                        toDisplayOffset(row.closedAt()),
                        toDisplayOffset(row.createdAt()),
                        toDisplayOffset(row.updatedAt()),
                        row.version()))
            .toList();
    return PageResponse.of(items, page.page(), page.size(), queries.countInquiries(filter));
  }

  @Transactional
  public OpsSupportInquiryDetailResponse detail(long operatorId, long inquiryId) {
    SupportInquiry inquiry = find(inquiryId);
    List<SupportInquiryMessage> history =
        messages.findByInquiryIdOrderByCreatedAtAscIdAsc(inquiryId);
    List<SupportInquiryAttachment> files =
        attachments.findByInquiryIdOrderByCreatedAtAscIdAsc(inquiryId);
    Names names = names(inquiry, history);
    auditLog.insert(operatorId, "SUPPORT_INQUIRY_VIEWED", TARGET, inquiryId, null, clock.instant());
    UserName author = names.users().get(inquiry.getRequesterUserId());
    return new OpsSupportInquiryDetailResponse(
        inquiry.getId(),
        inquiry.getOfficeId(),
        inquiry.getOfficeId() == null
            ? null
            : queries.officeName(inquiry.getOfficeId()).orElse(null),
        inquiry.getRequesterUserId(),
        author == null
            ? OpsDisplayNames.UNKNOWN
            : OpsDisplayNames.member(author.name(), author.withdrawn()),
        inquiry.getCategory().name(),
        inquiry.getStatus().name(),
        inquiry.getPriority().name(),
        inquiry.getAssignedOperatorId(),
        names.operators().get(inquiry.getAssignedOperatorId()),
        toDisplayOffset(inquiry.getResponseDueAt()),
        toDisplayOffset(inquiry.getAnsweredAt()),
        toDisplayOffset(inquiry.getClosedAt()),
        toDisplayOffset(inquiry.getCreatedAt()),
        inquiry.getContent(),
        inquiry.getRequestScope(),
        history.stream().map(message -> toMessage(message, names)).toList(),
        toAttachments(files, history, false),
        toDisplayOffset(inquiry.getUpdatedAt()),
        inquiry.getVersion());
  }

  @Transactional
  public OpsSupportInquiryAnswerResponse answer(
      long operatorId, long inquiryId, SupportInquiryOperatorReplyRequest body) {
    boolean needsCustomerReply = body.needsCustomerReply();
    boolean closeAfterReply = body.closeAfterReply();
    if (needsCustomerReply && closeAfterReply) {
      throw OpsQueryParams.invalid(
          "closeAfterReply",
          FieldErrorCode.NOT_ALLOWED,
          "needsCustomerReply와 closeAfterReply는 함께 true일 수 없습니다.");
    }
    String content = body.content().strip();
    SupportInquiry inquiry = lock(inquiryId);
    Instant now = clock.instant();
    inquiry.answered(content, needsCustomerReply, closeAfterReply, now);
    messages.saveAndFlush(SupportInquiryMessage.operatorReply(inquiryId, operatorId, content, now));
    SupportInquiry saved = inquiries.saveAndFlush(inquiry);
    auditLog.insert(
        operatorId,
        "SUPPORT_INQUIRY_ANSWERED",
        TARGET,
        inquiryId,
        "status=" + saved.getStatus(),
        now);
    notifyRequester(saved);
    List<SupportInquiryMessage> history =
        messages.findByInquiryIdOrderByCreatedAtAscIdAsc(inquiryId);
    Names names = names(saved, history);
    return new OpsSupportInquiryAnswerResponse(
        saved.getId(),
        saved.getStatus().name(),
        saved.getCategory().name(),
        saved.getPriority().name(),
        toDisplayOffset(saved.getResponseDueAt()),
        toDisplayOffset(saved.getAnsweredAt()),
        toDisplayOffset(saved.getClosedAt()),
        toDisplayOffset(saved.getCreatedAt()),
        history.stream().map(message -> toMessage(message, names)).toList(),
        toAttachments(
            attachments.findByInquiryIdOrderByCreatedAtAscIdAsc(inquiryId), history, true),
        saved.getOfficeId(),
        toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  @Transactional
  public OpsSupportInquiryAssignResponse assign(long operatorId, long inquiryId, long assigneeId) {
    SupportInquiry inquiry = lock(inquiryId);
    inquiry.requireOpenForOperator();
    if (!accounts.isActiveWithRole(assigneeId, OperatorRole.SUPPORT)) {
      throw new BusinessException(ErrorCode.ASSIGNEE_NOT_ELIGIBLE);
    }
    Instant now = clock.instant();
    inquiry.assignTo(assigneeId, now);
    SupportInquiry saved = inquiries.saveAndFlush(inquiry);
    auditLog.insert(
        operatorId, "SUPPORT_INQUIRY_ASSIGNED", TARGET, inquiryId, "assignee=" + assigneeId, now);
    return new OpsSupportInquiryAssignResponse(
        saved.getId(),
        saved.getStatus().name(),
        saved.getAssignedOperatorId(),
        saved.getOfficeId(),
        toDisplayOffset(saved.getCreatedAt()),
        toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  @Transactional
  public OpsSupportInquiryNoteResponse addNote(long operatorId, long inquiryId, String content) {
    SupportInquiry inquiry = lock(inquiryId);
    inquiry.requireOpenForOperator();
    Instant now = clock.instant();
    SupportInquiryMessage note =
        messages.saveAndFlush(
            SupportInquiryMessage.internalNote(inquiryId, operatorId, content.strip(), now));
    inquiry.touch(now);
    SupportInquiry saved = inquiries.saveAndFlush(inquiry);
    auditLog.insert(
        operatorId,
        "SUPPORT_INQUIRY_NOTE_ADDED",
        TARGET,
        inquiryId,
        "messageId=" + note.getId(),
        now);
    return new OpsSupportInquiryNoteResponse(
        note.getId(),
        inquiryId,
        note.getVisibility().name(),
        note.getContent(),
        toDisplayOffset(note.getCreatedAt()),
        saved.getOfficeId(),
        toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  @Transactional
  public OpsSupportInquiryAttachmentResponse upload(
      long operatorId, long inquiryId, MultipartFile file, String messageIdInput) {
    SupportInquiry inquiry = lock(inquiryId);
    inquiry.requireOpenForOperator();
    if (file == null || file.isEmpty()) {
      throw OpsQueryParams.invalid("file", FieldErrorCode.REQUIRED, "파일이 필요합니다.");
    }
    Long messageId = operatorMessage(inquiryId, messageIdInput);
    String fileName = AttachmentRules.fileName(file.getOriginalFilename());
    String mediaType;
    try {
      mediaType = AttachmentRules.mediaTypeFor(fileName, file.getContentType());
    } catch (BusinessException e) {
      throw fileNotAllowed();
    }
    if (file.getSize() > AttachmentRules.MAX_BYTES) {
      throw OpsQueryParams.invalid("file", FieldErrorCode.OUT_OF_RANGE, "파일은 20MB 이하여야 합니다.");
    }
    if (attachments.countByInquiryIdAndUploadedByOperatorIdIsNotNull(inquiryId)
        >= OPERATOR_ATTACHMENT_MAX) {
      throw OpsQueryParams.invalid("file", FieldErrorCode.OUT_OF_RANGE, "운영자 첨부는 문의당 5개까지입니다.");
    }
    byte[] content = read(file);
    try {
      AttachmentRules.requireSignatureMatches(mediaType, content);
    } catch (BusinessException e) {
      throw fileNotAllowed();
    }
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
          attachments.saveAndFlush(
              SupportInquiryAttachment.uploadedByOperator(
                  inquiryId, messageId, operatorId, key, fileName, mediaType, content.length, now));
      inquiry.touch(now);
      inquiries.saveAndFlush(inquiry);
      auditLog.insert(
          operatorId,
          "SUPPORT_ATTACHMENT_UPLOADED",
          TARGET,
          inquiryId,
          "attachmentId=" + saved.getId(),
          now);
      scanDispatcher.dispatch(saved.getId());
      return new OpsSupportInquiryAttachmentResponse(
          saved.getId(),
          saved.getInquiryId(),
          saved.getMessageId(),
          saved.getScanStatus().name(),
          saved.getFileName(),
          saved.getMediaType(),
          saved.getSizeBytes(),
          toDisplayOffset(saved.getCreatedAt()));
    } catch (RuntimeException e) {
      objectStorage.delete(key);
      throw e;
    }
  }

  @Transactional
  public Download download(long operatorId, long inquiryId, long attachmentId) {
    find(inquiryId);
    SupportInquiryAttachment attachment =
        attachments
            .findByIdAndInquiryId(attachmentId, inquiryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (attachment.getScanStatus() == ScanStatus.REJECTED) {
      throw new BusinessException(ErrorCode.ATTACHMENT_REJECTED);
    }
    if (attachment.getScanStatus() != ScanStatus.CLEAN) {
      throw new BusinessException(ErrorCode.ATTACHMENT_NOT_READY);
    }
    byte[] content =
        objectStorage
            .find(attachment.getObjectKey())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    auditLog.insert(
        operatorId,
        "SUPPORT_ATTACHMENT_DOWNLOADED",
        TARGET,
        inquiryId,
        "attachmentId=" + attachmentId,
        clock.instant());
    return new Download(attachment.getFileName(), attachment.getMediaType(), content);
  }

  @Transactional
  public OpsSupportInquiryPriorityResponse changePriority(
      long operatorId, long inquiryId, String ifMatch, SupportInquiryPriorityRequest body) {
    long expected = IfMatchVersion.parse(ifMatch);
    SupportInquiry inquiry = lock(inquiryId);
    if (!Objects.equals(inquiry.getVersion(), expected)) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    InquiryPriority next = InquiryPriority.valueOf(body.priority());
    Instant now = clock.instant();
    Instant recalculated =
        inquiry.getAnsweredAt() == null && !queries.linkedToPrivacyRequest(inquiryId)
            ? ResponseDueCalculator.firstResponseDue(inquiry.getCreatedAt(), next)
            : null;
    String previous = inquiry.getPriority().name();
    inquiry.changePriority(next, recalculated, now);
    SupportInquiry saved = inquiries.saveAndFlush(inquiry);
    String reason = body.reason() == null || body.reason().isBlank() ? null : body.reason().strip();
    auditLog.insert(
        operatorId,
        "SUPPORT_INQUIRY_PRIORITY_CHANGED",
        TARGET,
        inquiryId,
        "from=" + previous + " to=" + next + (reason == null ? "" : " reason=" + reason),
        now);
    return new OpsSupportInquiryPriorityResponse(
        saved.getId(),
        saved.getPriority().name(),
        toDisplayOffset(saved.getResponseDueAt()),
        toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  private void notifyRequester(SupportInquiry inquiry) {
    Map<Long, UserName> users = queries.userNames(List.of(inquiry.getRequesterUserId()));
    UserName requester = users.get(inquiry.getRequesterUserId());
    if (requester == null || requester.withdrawn()) {
      return;
    }
    notifications.publish(
        new NotificationPublisher.Notice(
            inquiry.getOfficeId(),
            inquiry.getRequesterUserId(),
            "SUPPORT_REPLY",
            "문의에 답변이 등록되었습니다.",
            "SUPPORT_TICKET",
            inquiry.getId()));
  }

  private record Names(Map<Long, String> operators, Map<Long, UserName> users) {}

  private Names names(SupportInquiry inquiry, List<SupportInquiryMessage> history) {
    Set<Long> operatorIds = new HashSet<>();
    Set<Long> userIds = new HashSet<>();
    userIds.add(inquiry.getRequesterUserId());
    if (inquiry.getAssignedOperatorId() != null) {
      operatorIds.add(inquiry.getAssignedOperatorId());
    }
    for (SupportInquiryMessage message : history) {
      if (message.getAuthorOperatorId() != null) {
        operatorIds.add(message.getAuthorOperatorId());
      }
      if (message.getAuthorUserId() != null) {
        userIds.add(message.getAuthorUserId());
      }
    }
    return new Names(directory.operatorNames(operatorIds), queries.userNames(userIds));
  }

  private static OpsSupportMessage toMessage(SupportInquiryMessage message, Names names) {
    String author;
    if (message.getAuthorOperatorId() != null) {
      author =
          names.operators().getOrDefault(message.getAuthorOperatorId(), OpsDisplayNames.UNKNOWN);
    } else {
      UserName user = names.users().get(message.getAuthorUserId());
      author =
          user == null
              ? OpsDisplayNames.UNKNOWN
              : OpsDisplayNames.member(user.name(), user.withdrawn());
    }
    return new OpsSupportMessage(
        message.getId(),
        message.getVisibility().name(),
        message.getAuthorType().name(),
        author,
        message.getContent(),
        toDisplayOffset(message.getCreatedAt()));
  }

  /**
   * 첨부의 공개 구분은 연결된 메시지를 따른다. 메시지 없는 첨부는 문의자가 올린 것이면 공개, 운영자가 올린 것이면 고객에게 보이지 않으므로 내부다.
   * publicCleanOnly이면 답변 응답용으로 검사를 통과한 공개 첨부만 남긴다.
   */
  private static List<OpsSupportAttachment> toAttachments(
      List<SupportInquiryAttachment> files,
      List<SupportInquiryMessage> history,
      boolean publicCleanOnly) {
    Map<Long, MessageVisibility> visibility = new HashMap<>();
    for (SupportInquiryMessage message : history) {
      visibility.put(message.getId(), message.getVisibility());
    }
    return files.stream()
        .map(
            file -> {
              MessageVisibility shown =
                  file.getMessageId() == null
                      ? (file.getUploadedByUserId() != null
                          ? MessageVisibility.PUBLIC
                          : MessageVisibility.INTERNAL)
                      : visibility.getOrDefault(file.getMessageId(), MessageVisibility.INTERNAL);
              return new OpsSupportAttachment(
                  file.getId(),
                  file.getMessageId(),
                  shown.name(),
                  file.getFileName(),
                  file.getMediaType(),
                  file.getSizeBytes(),
                  file.getScanStatus().name(),
                  toDisplayOffset(file.getCreatedAt()));
            })
        .filter(
            item ->
                !publicCleanOnly
                    || (item.visibility().equals("PUBLIC") && item.scanStatus().equals("CLEAN")))
        .toList();
  }

  private Long operatorMessage(long inquiryId, String messageIdInput) {
    if (messageIdInput == null || messageIdInput.isBlank()) {
      return null;
    }
    long messageId;
    try {
      messageId = Long.parseLong(messageIdInput.strip());
    } catch (NumberFormatException e) {
      throw OpsQueryParams.invalid("messageId", FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
    }
    return messages
        .findByIdAndInquiryIdAndAuthorOperatorIdIsNotNull(messageId, inquiryId)
        .map(SupportInquiryMessage::getId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private SupportInquiry find(long inquiryId) {
    return inquiries
        .findById(inquiryId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private SupportInquiry lock(long inquiryId) {
    return inquiries
        .findForUpdateById(inquiryId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private static BusinessException fileNotAllowed() {
    return OpsQueryParams.invalid("file", FieldErrorCode.NOT_ALLOWED, "PDF·JPG·PNG 파일만 올릴 수 있습니다.");
  }

  private static byte[] read(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw OpsQueryParams.invalid("file", FieldErrorCode.INVALID_FORMAT, "파일을 읽을 수 없습니다.");
    }
  }
}
