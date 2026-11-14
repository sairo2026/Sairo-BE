package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "본인의 최신 참여 요청·초대 상태")
public record MembershipStatusResponse(
    Long membershipId,
    Long officeId,
    String officeName,
    String role,
    String status,
    String rejectionReason,
    Boolean resolved,
    String requestSource,
    OffsetDateTime expiresAt,
    OffsetDateTime invitationAcceptedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
