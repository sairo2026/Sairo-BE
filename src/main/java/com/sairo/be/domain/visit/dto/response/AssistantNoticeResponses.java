package com.sairo.be.domain.visit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public final class AssistantNoticeResponses {

  private AssistantNoticeResponses() {}

  public record AssistantNoticeResponse(
      Long noticeId,
      Long scheduleItemId,
      String recipientType,
      String noticeMethod,
      @Schema(nullable = true) OffsetDateTime notifiedAt,
      boolean recordedAfterTheFact,
      String recipientName,
      @Schema(nullable = true) String missingExceptionReason,
      @Schema(nullable = true) String followupAction,
      OffsetDateTime recordedAt,
      Long recordedByMembershipId,
      @Schema(nullable = true) Long confirmedByMembershipId,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      @Schema(nullable = true) String supervisorAdditionalNotice,
      @Schema(nullable = true) OffsetDateTime supervisorAdditionalNoticedAt) {}

  public record AssistantNoticeListResponse(
      List<NoticeItem> items, List<RequiredRecipient> requiredRecipients) {}

  public record NoticeItem(
      Long noticeId,
      String recipientType,
      String recipientName,
      String noticeMethod,
      @Schema(nullable = true) OffsetDateTime notifiedAt,
      @Schema(nullable = true) String missingExceptionReason,
      @Schema(nullable = true) String followupAction,
      boolean recordedAfterTheFact,
      @Schema(nullable = true) OffsetDateTime confirmedAt) {}

  public record RequiredRecipient(
      String recipientType, @Schema(nullable = true) Long candidatePropertyId, boolean recorded) {}

  public record AssistantNoticeSupervisionListResponse(
      List<SupervisionItem> items, int page, int size, long totalElements) {}

  public record SupervisionItem(
      Long noticeId,
      Long scheduleItemId,
      Long assistantMembershipId,
      String recipientType,
      @Schema(nullable = true) OffsetDateTime notifiedAt,
      boolean recordedAfterTheFact,
      @Schema(nullable = true) OffsetDateTime confirmedAt) {}
}
