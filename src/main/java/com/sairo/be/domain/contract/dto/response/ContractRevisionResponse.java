package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

@Schema(
    description =
        "계약 핵심정보 버전이다(ContractRevisionResponse, ContractRevisionListResponse의 항목). 스냅샷 키는 체결 버전과 같다.")
public record ContractRevisionResponse(
    Long revisionId,
    int revisionNo,
    @Schema(allowableValues = {"SIGNED", "CORRECTED"}) String revisionType,
    Long contractId,
    @Schema(nullable = true, type = "object") JsonNode previousSnapshot,
    @Schema(type = "object") JsonNode currentSnapshot,
    @Schema(nullable = true) String reason,
    OffsetDateTime effectiveAt,
    OffsetDateTime recordedAt,
    Long recordedByMembershipId,
    @Schema(nullable = true) Long supersedesRevisionId,
    long version,
    Long officeId) {}
