package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;

public record VisitRequestResponse(
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
    long version) {}
