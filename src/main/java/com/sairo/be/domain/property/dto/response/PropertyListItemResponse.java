package com.sairo.be.domain.property.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "매물 목록 항목")
public record PropertyListItemResponse(
    Long propertyId,
    String name,
    @Schema(nullable = true) String address,
    @Schema(nullable = true) String addressDetail,
    Long categoryId,
    String categoryName,
    String registrationStage,
    @Schema(nullable = true) Long managingMembershipId,
    @Schema(nullable = true) String managingMemberName,
    String occupancyStatus,
    List<String> ownerNames,
    List<String> occupantNames,
    @Schema(nullable = true) LocalDate leaseEndDate,
    @Schema(nullable = true) String leaseEndSource,
    @Schema(nullable = true) String memo,
    List<ActiveOfferSummary> activeOfferSummary,
    OffsetDateTime updatedAt,
    Long officeId,
    OffsetDateTime createdAt,
    Long version) {

  public record ActiveOfferSummary(
      String dealType,
      String status,
      @Schema(nullable = true) Long salePrice,
      @Schema(nullable = true) Long depositAmount,
      @Schema(nullable = true) Long monthlyRentAmount,
      @Schema(nullable = true) Long premiumAmount) {}
}
