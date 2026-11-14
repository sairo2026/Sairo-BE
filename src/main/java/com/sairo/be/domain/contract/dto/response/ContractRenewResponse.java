package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "재계약 결과다(ContractRenewResponse). 직전 계약은 바뀌지 않는다.")
public record ContractRenewResponse(
    Long contractId,
    String contractStatus,
    Long previousContractId,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    long version) {}
