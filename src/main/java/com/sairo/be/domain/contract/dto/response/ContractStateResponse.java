package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(
    description =
        "계약 상태 변경 결과다(ContractDraftMutationResponse, ContractDraftUpdateResponse, ContractSignResponse, ContractStatusUpdateResponse).")
public record ContractStateResponse(
    Long contractId,
    @Schema(allowableValues = {"DRAFT", "SIGNED", "ENDED", "TERMINATED", "CANCELLED", "VOID"})
        String contractStatus,
    long version,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
