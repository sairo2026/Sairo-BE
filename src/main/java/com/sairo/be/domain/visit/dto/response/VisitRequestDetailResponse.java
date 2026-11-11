package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record VisitRequestDetailResponse(
    Long requestId,
    Long conditionId,
    Long approvalId,
    Long propertyId,
    Long propertyOfficeId,
    boolean crossOffice,
    Long accessGrantId,
    Long applicantOfficeId,
    Long applicantMembershipId,
    Long coordinationId,
    Long candidatePropertyId,
    String requestKind,
    Long replacesScheduleItemId,
    OffsetDateTime requestedStartAt,
    OffsetDateTime requestedEndAt,
    int expectedDurationMinutes,
    Integer partySize,
    String requestNote,
    String status,
    String statusReason,
    OffsetDateTime submittedAt,
    OffsetDateTime closedAt,
    OffsetDateTime updatedAt,
    long version,
    List<StatusHistory> statusHistory,
    List<Selection> selections,
    List<Revalidation> revalidations) {

  public record StatusHistory(
      String fromStatus,
      String toStatus,
      String source,
      String reason,
      OffsetDateTime effectiveAt) {}

  public record Selection(Long selectionId, int comparedRequestCount, OffsetDateTime selectedAt) {}

  public record Revalidation(
      Long revalidationId,
      Long selectionId,
      Long conditionId,
      String result,
      String failureCode,
      String failureDetail,
      OffsetDateTime checkedAt) {}
}
