package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "보관권한 발급 결과")
public record ArchiveGrantCreateResponse(
    Long grantId,
    @Schema(description = "대상 사무소") Long officeId,
    @Schema(nullable = true) OffsetDateTime expiresAt,
    boolean revoked) {}
