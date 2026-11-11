package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "중복 제외 결과다(ContractDuplicateExclusionResponse). 계약과 서류, 체결 이력은 그대로 남는다.")
public record ContractDuplicateExclusionResponse(
    Long contractId,
    Long duplicateOfContractId,
    OffsetDateTime excludedAt,
    Long officeId,
    long version) {}
