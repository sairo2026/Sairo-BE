package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "초대 발급·재발송 결과")
public record MembershipInvitationResponse(
    Long membershipId,
    OffsetDateTime expiresAt,
    String status,
    String role,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
