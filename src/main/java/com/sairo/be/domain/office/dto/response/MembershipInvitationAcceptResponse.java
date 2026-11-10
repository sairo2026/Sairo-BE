package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "초대 수락 결과")
public record MembershipInvitationAcceptResponse(
    Long membershipId,
    OffsetDateTime invitationAcceptedAt,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
