package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record AccessApprovalHistoryItem(
    Long approvalId,
    Long propertyId,
    String approverType,
    String approverName,
    String approverPhone,
    String approvalRole,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil,
    String source,
    Long recordedByMembershipId,
    OffsetDateTime recordedAt,
    OffsetDateTime confirmedAt,
    String recordedByName,
    List<Object> timeSlots,
    OffsetDateTime revokedAt,
    String revocationReason,
    OffsetDateTime revocationRecordedAt,
    Long revokedByUserId) {}
