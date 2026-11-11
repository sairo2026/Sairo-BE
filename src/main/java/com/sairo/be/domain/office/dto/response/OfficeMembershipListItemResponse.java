package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "구성원 목록 항목. 대표가 아니면 membershipId, displayName, role, status만 값이 있다.")
public record OfficeMembershipListItemResponse(
    Long membershipId,
    Long userId,
    String displayName,
    String role,
    String status,
    OffsetDateTime approvedAt,
    String requestSource,
    OffsetDateTime expiresAt,
    OffsetDateTime invitationAcceptedAt,
    String qualificationNumber,
    Boolean employmentReportConfirmed,
    Boolean trainingConfirmed,
    Boolean billable,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
