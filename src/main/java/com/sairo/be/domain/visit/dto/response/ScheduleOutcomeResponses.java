package com.sairo.be.domain.visit.dto.response;

import com.sairo.be.domain.visit.dto.response.VisitCoordinationDetailResponse.Alternative;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ScheduleOutcomeResponses {

  private ScheduleOutcomeResponses() {}

  public record AffectedScheduleItem(
      Long scheduleItemId, Long candidatePropertyId, String state, String reason) {}

  public record CandidateRef(Long candidatePropertyId, Long propertyId, int sortOrder) {}

  public record VisitScheduleItemResultResponse(
      Long itemId,
      Long coordinationId,
      String result,
      @Schema(nullable = true) OffsetDateTime actualVisitedAt,
      @Schema(nullable = true) OffsetDateTime resultRecordedAt,
      @Schema(nullable = true) OffsetDateTime reviewWindowEndsAt,
      int correctionCount,
      String coordinationStatus,
      List<AffectedScheduleItem> affectedItems,
      OffsetDateTime updatedAt) {}

  public record VisitScheduleItemResultHistoryResponse(List<ResultHistoryItem> items) {}

  public record ResultHistoryItem(
      Long historyId,
      @Schema(nullable = true) String previousResult,
      String newResult,
      @Schema(nullable = true) OffsetDateTime previousActualVisitedAt,
      @Schema(nullable = true) OffsetDateTime newActualVisitedAt,
      @Schema(nullable = true) String correctionReason,
      OffsetDateTime effectiveAt,
      OffsetDateTime recordedAt,
      String recordedByName) {}

  public record CancelledScheduleItem(
      Long itemId,
      Long candidatePropertyId,
      @Schema(nullable = true) OffsetDateTime scheduledStartAt,
      @Schema(nullable = true) OffsetDateTime scheduledEndAt,
      String result) {}

  public record RescheduledScheduleItem(
      Long itemId,
      Long candidatePropertyId,
      @Schema(nullable = true) OffsetDateTime scheduledStartAt,
      @Schema(nullable = true) OffsetDateTime scheduledEndAt,
      String result,
      String state) {}

  public record VisitCoordinationRescheduleResponse(
      Long coordinationId,
      String status,
      Long customerId,
      String customerName,
      Long managerMembershipId,
      LocalDate periodStartDate,
      LocalDate periodEndDate,
      List<CandidateRef> candidateProperties,
      List<RescheduledScheduleItem> scheduleItems,
      List<Long> fixedScheduleItemIds,
      List<AffectedScheduleItem> affectedItems,
      List<Alternative> alternatives,
      List<Long> noAlternativeScheduleItemIds,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      Long version,
      Long officeId) {}

  public record VisitCoordinationCancelResponse(
      Long coordinationId,
      String status,
      Long customerId,
      String customerName,
      Long managerMembershipId,
      LocalDate periodStartDate,
      LocalDate periodEndDate,
      List<CandidateRef> candidateProperties,
      List<CancelledScheduleItem> scheduleItems,
      List<AffectedScheduleItem> affectedItems,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      Long version,
      Long officeId) {}

  public record VisitAccessRevocationResponse(
      Long approvalId,
      Long coordinationId,
      Long officeId,
      OffsetDateTime revokedAt,
      OffsetDateTime revocationRecordedAt,
      Long revokedByUserId,
      String status,
      Long version) {}

  @Schema(description = "반환권한으로 기존 방문을 취소한 뒤의 조율 상태")
  public record ReturnVisitCancelResponse(
      Long coordinationId, Long officeId, String status, Long version) {}

  @Schema(description = "반환권한으로 사후 기록한 방문 결과와 조율 상태")
  public record ReturnVisitResultResponse(
      Long itemId,
      Long coordinationId,
      Long officeId,
      String result,
      @Schema(nullable = true) OffsetDateTime actualVisitedAt,
      OffsetDateTime effectiveAt,
      OffsetDateTime recordedAt,
      String status,
      Long version) {}

  public record VisitCoordinationCloneResponse(
      Long coordinationId,
      String status,
      List<CandidateRef> candidateProperties,
      Long officeId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      Long version) {}
}
