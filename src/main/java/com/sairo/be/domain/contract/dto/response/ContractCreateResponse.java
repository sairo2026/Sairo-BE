package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "즉시 체결 등록 결과다(ContractCreateResponse).")
public record ContractCreateResponse(
    Long contractId,
    String contractStatus,
    List<PartyResponse> sellerLandlordParties,
    List<PartyResponse> buyerTenantParties,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    long version) {}
