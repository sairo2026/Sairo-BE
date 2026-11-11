package com.sairo.be.domain.support.mapper;

import com.sairo.be.domain.support.dto.response.SupportInquiryAttachmentResponse;
import com.sairo.be.domain.support.dto.response.SupportInquiryCreateResponse;
import com.sairo.be.domain.support.dto.response.SupportInquiryListItem;
import com.sairo.be.domain.support.dto.response.SupportInquiryReplyResponse;
import com.sairo.be.domain.support.dto.response.SupportInquiryResponse;
import com.sairo.be.domain.support.dto.response.SupportInquiryResponse.AttachmentItem;
import com.sairo.be.domain.support.dto.response.SupportInquiryResponse.MessageItem;
import com.sairo.be.domain.support.entity.SupportInquiry;
import com.sairo.be.domain.support.entity.SupportInquiryAttachment;
import com.sairo.be.domain.support.entity.SupportInquiryMessage;
import com.sairo.be.global.support.TimeRules;
import java.util.List;
import java.util.Map;

public final class SupportInquiryMapper {

  private SupportInquiryMapper() {}

  public static SupportInquiryCreateResponse toCreate(SupportInquiry inquiry) {
    return new SupportInquiryCreateResponse(
        inquiry.getId(),
        inquiry.getStatus().name(),
        TimeRules.toDisplayOffset(inquiry.getResponseDueAt()),
        inquiry.getOfficeId(),
        TimeRules.toDisplayOffset(inquiry.getCreatedAt()),
        TimeRules.toDisplayOffset(inquiry.getUpdatedAt()),
        inquiry.getVersion());
  }

  public static SupportInquiryListItem toListItem(
      SupportInquiry inquiry, Map<Long, String> operatorNames) {
    return new SupportInquiryListItem(
        inquiry.getId(),
        inquiry.getOfficeId(),
        inquiry.getCategory().name(),
        inquiry.getStatus().name(),
        inquiry.getPriority().name(),
        operatorName(inquiry, operatorNames),
        TimeRules.toDisplayOffset(inquiry.getResponseDueAt()),
        TimeRules.toDisplayOffset(inquiry.getAnsweredAt()),
        TimeRules.toDisplayOffset(inquiry.getClosedAt()),
        TimeRules.toDisplayOffset(inquiry.getCreatedAt()),
        TimeRules.toDisplayOffset(inquiry.getUpdatedAt()),
        inquiry.getVersion());
  }

  public static SupportInquiryResponse toDetail(
      SupportInquiry inquiry,
      List<SupportInquiryMessage> publicMessages,
      List<SupportInquiryAttachment> visibleAttachments,
      Map<Long, String> operatorNames) {
    return new SupportInquiryResponse(
        inquiry.getId(),
        inquiry.getStatus().name(),
        inquiry.getCategory().name(),
        inquiry.getPriority().name(),
        operatorName(inquiry, operatorNames),
        TimeRules.toDisplayOffset(inquiry.getResponseDueAt()),
        TimeRules.toDisplayOffset(inquiry.getAnsweredAt()),
        TimeRules.toDisplayOffset(inquiry.getClosedAt()),
        TimeRules.toDisplayOffset(inquiry.getCreatedAt()),
        inquiry.getContent(),
        inquiry.getRequestScope(),
        publicMessages.stream().map(message -> toMessage(message, operatorNames)).toList(),
        visibleAttachments.stream().map(SupportInquiryMapper::toAttachmentItem).toList(),
        inquiry.getOfficeId(),
        TimeRules.toDisplayOffset(inquiry.getUpdatedAt()),
        inquiry.getVersion());
  }

  public static SupportInquiryReplyResponse toReply(
      SupportInquiry inquiry, SupportInquiryMessage message) {
    return new SupportInquiryReplyResponse(
        message.getId(),
        inquiry.getId(),
        message.getVisibility().name(),
        inquiry.getStatus().name(),
        message.getContent(),
        TimeRules.toDisplayOffset(message.getCreatedAt()),
        inquiry.getOfficeId(),
        TimeRules.toDisplayOffset(inquiry.getUpdatedAt()),
        inquiry.getVersion());
  }

  public static SupportInquiryAttachmentResponse toAttachment(SupportInquiryAttachment attachment) {
    return new SupportInquiryAttachmentResponse(
        attachment.getId(),
        attachment.getInquiryId(),
        attachment.getMessageId(),
        attachment.getScanStatus().name(),
        attachment.getFileName(),
        attachment.getMediaType(),
        attachment.getSizeBytes(),
        TimeRules.toDisplayOffset(attachment.getCreatedAt()));
  }

  private static MessageItem toMessage(
      SupportInquiryMessage message, Map<Long, String> operatorNames) {
    String displayName =
        message.getAuthorOperatorId() == null
            ? null
            : operatorNames.get(message.getAuthorOperatorId());
    return new MessageItem(
        message.getId(),
        message.getVisibility().name(),
        message.getContent(),
        message.getAuthorType().name(),
        displayName,
        TimeRules.toDisplayOffset(message.getCreatedAt()));
  }

  private static AttachmentItem toAttachmentItem(SupportInquiryAttachment attachment) {
    return new AttachmentItem(
        attachment.getId(),
        attachment.getFileName(),
        attachment.getMediaType(),
        attachment.getSizeBytes(),
        attachment.getScanStatus().name(),
        TimeRules.toDisplayOffset(attachment.getCreatedAt()),
        attachment.getMessageId());
  }

  private static String operatorName(SupportInquiry inquiry, Map<Long, String> operatorNames) {
    return inquiry.getAssignedOperatorId() == null
        ? null
        : operatorNames.get(inquiry.getAssignedOperatorId());
  }
}
