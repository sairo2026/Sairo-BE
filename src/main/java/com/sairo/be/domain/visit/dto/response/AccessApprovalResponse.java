package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record AccessApprovalResponse(
    Long approvalId,
    Long propertyId,
    String approverType,
    String approverName,
    String approverPhone,
    String approvalRole,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil,
    List<Object> timeSlots,
    String source,
    Long recordedByMembershipId,
    String recordedByName,
    OffsetDateTime confirmedAt,
    OffsetDateTime createdAt,
    Long officeId,
    long version,
    OffsetDateTime revokedAt,
    String revocationReason,
    OffsetDateTime revocationRecordedAt,
    Long revokedByUserId) {}
