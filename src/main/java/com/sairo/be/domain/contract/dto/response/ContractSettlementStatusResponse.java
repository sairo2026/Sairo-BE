package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "잔금 상태 변경 결과다(ContractSettlementStatusResponse).")
public record ContractSettlementStatusResponse(
    Long contractId,
    @Schema(allowableValues = {"UNDECIDED", "SCHEDULED", "PARTIAL", "COMPLETE", "NONE"})
        String settlementStatus,
    long version,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
