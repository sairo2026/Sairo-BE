package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "계약 목록(ContractListResponse)의 항목이다.")
public record ContractListItem(
    Long contractId,
    @Schema(description = "매물 주소. 주소가 비어 있는 매물은 매물명") String propertyAddress,
    @Schema(allowableValues = {"SALE", "JEONSE", "MONTHLY"}) String dealType,
    String contractStatus,
    @Schema(nullable = true) String leasePeriodStatus,
    String settlementStatus,
    @Schema(nullable = true) LocalDate signedDate,
    @Schema(nullable = true) String sellerLandlordName,
    int sellerLandlordPartyCount,
    @Schema(nullable = true) String buyerTenantName,
    int buyerTenantPartyCount,
    boolean excluded,
    @Schema(nullable = true) LocalDate leaseEndDate,
    Long managingMembershipId,
    String managingMemberName,
    List<String> missingDocumentTypes,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    long version) {}
