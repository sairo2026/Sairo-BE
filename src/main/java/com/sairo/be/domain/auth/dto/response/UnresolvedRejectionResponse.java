package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "미해결 참여거절·신규반려")
public record UnresolvedRejectionResponse(
    @Schema(description = "REGISTRATION_REJECTED 또는 MEMBERSHIP_REJECTED") String type,
    @Schema(description = "사유") String reason) {}
