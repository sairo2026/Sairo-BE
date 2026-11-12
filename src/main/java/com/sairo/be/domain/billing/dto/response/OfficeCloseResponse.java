package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "사무소 종료 결과")
public record OfficeCloseResponse(
    Long officeId,
    @Schema(allowableValues = {"ENDED"}) String subscriptionStatus,
    Long returnGrantId,
    OffsetDateTime returnExpiresAt,
    @Schema(allowableValues = {"NONE", "REQUESTED"}) String archiveRequestStatus) {}
