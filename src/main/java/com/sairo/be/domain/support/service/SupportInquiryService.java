package com.sairo.be.domain.support.service;

import com.sairo.be.domain.support.dto.response.SupportInquiryCreateResponse;
import com.sairo.be.domain.support.dto.response.SupportInquiryListItem;
import com.sairo.be.domain.support.dto.response.SupportInquiryReplyResponse;
import com.sairo.be.domain.support.dto.response.SupportInquiryResponse;
import com.sairo.be.domain.support.entity.InquiryCategory;
import com.sairo.be.domain.support.entity.SupportInquiry;
import com.sairo.be.domain.support.entity.SupportInquiryAttachment;
import com.sairo.be.domain.support.entity.SupportInquiryMessage;
import com.sairo.be.domain.support.mapper.SupportInquiryMapper;
import com.sairo.be.domain.support.repository.SupportDirectoryRepository;
import com.sairo.be.domain.support.repository.SupportInquiryAttachmentRepository;
import com.sairo.be.domain.support.repository.SupportInquiryMessageRepository;
import com.sairo.be.domain.support.repository.SupportInquiryRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class SupportInquiryService {

  private final SupportInquiryRepository inquiryRepository;
  private final SupportInquiryMessageRepository messageRepository;
  private final SupportInquiryAttachmentRepository attachmentRepository;
  private final SupportDirectoryRepository directoryRepository;
  private final Clock clock;

  public SupportInquiryService(
      SupportInquiryRepository inquiryRepository,
      SupportInquiryMessageRepository messageRepository,
      SupportInquiryAttachmentRepository attachmentRepository,
      SupportDirectoryRepository directoryRepository,
      Clock clock) {
    this.inquiryRepository = inquiryRepository;
    this.messageRepository = messageRepository;
    this.attachmentRepository = attachmentRepository;
    this.directoryRepository = directoryRepository;
    this.clock = clock;
  }

  @Transactional
  public SupportInquiryCreateResponse create(StaffPrincipal principal, JsonNode body) {
    SupportRequestParser.InquiryInput input = SupportRequestParser.inquiry(body);
    Instant now = clock.instant();
    SupportInquiry inquiry =
        inquiryRepository.saveAndFlush(
            SupportInquiry.open(
                principal.userId(),
                principal.officeId(),
                input.category(),
                input.content(),
                input.requestScope(),
                ResponseDueCalculator.firstResponseDue(now),
                now));
    return SupportInquiryMapper.toCreate(inquiry);
  }

  @Transactional
  public Long openForPrivacyRequest(
      Long userId, Long officeId, String content, String requestScope, Instant legalDueAt) {
    Instant now = clock.instant();
    SupportInquiry inquiry =
        inquiryRepository.saveAndFlush(
            SupportInquiry.open(
                userId, officeId, InquiryCategory.PRIVACY, content, requestScope, legalDueAt, now));
    return inquiry.getId();
  }

  @Transactional(readOnly = true)
  public PageResponse<SupportInquiryListItem> list(
      StaffPrincipal principal, Integer page, Integer size) {
    PageQuery query = PageQuery.of(page, size);
    Page<SupportInquiry> found =
        inquiryRepository.findByRequesterUserId(
            principal.userId(),
            query.toPageable(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    Set<Long> operatorIds = new HashSet<>();
    found.forEach(
        inquiry -> {
          if (inquiry.getAssignedOperatorId() != null) {
            operatorIds.add(inquiry.getAssignedOperatorId());
          }
        });
    Map<Long, String> names = directoryRepository.operatorNames(operatorIds);
    return PageResponse.from(found, inquiry -> SupportInquiryMapper.toListItem(inquiry, names));
  }

  @Transactional(readOnly = true)
  public SupportInquiryResponse detail(StaffPrincipal principal, Long inquiryId) {
    SupportInquiry inquiry =
        inquiryRepository
            .findByIdAndRequesterUserId(inquiryId, principal.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    List<SupportInquiryMessage> messages = messageRepository.findPublicByInquiry(inquiryId);
    List<SupportInquiryAttachment> attachments =
        attachmentRepository.findVisibleToRequester(inquiryId);
    Set<Long> operatorIds = new HashSet<>();
    if (inquiry.getAssignedOperatorId() != null) {
      operatorIds.add(inquiry.getAssignedOperatorId());
    }
    messages.forEach(
        message -> {
          if (message.getAuthorOperatorId() != null) {
            operatorIds.add(message.getAuthorOperatorId());
          }
        });
    return SupportInquiryMapper.toDetail(
        inquiry, messages, attachments, directoryRepository.operatorNames(operatorIds));
  }

  @Transactional
  public SupportInquiryReplyResponse reply(
      StaffPrincipal principal, Long inquiryId, JsonNode body) {
    String content = SupportRequestParser.reply(body);
    SupportInquiry inquiry =
        inquiryRepository
            .findForUpdateByRequester(inquiryId, principal.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = clock.instant();
    SupportInquiryMessage message =
        messageRepository.saveAndFlush(
            SupportInquiryMessage.requesterReply(inquiryId, principal.userId(), content, now));
    inquiry.requesterAdded(now);
    SupportInquiry saved = inquiryRepository.saveAndFlush(inquiry);
    return SupportInquiryMapper.toReply(saved, message);
  }
}
