package com.sairo.be.domain.property.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "매물 수정 결과")
public record PropertyUpdateResponse(
    Long propertyId,
    String name,
    @Schema(nullable = true) String address,
    @Schema(nullable = true) String addressDetail,
    Long categoryId,
    String categoryName,
    @Schema(nullable = true) Long managingMembershipId,
    @Schema(nullable = true) String managingMemberName,
    String registrationStage,
    @Schema(nullable = true) BigDecimal exclusiveAreaSqm,
    @Schema(nullable = true) BigDecimal supplyAreaSqm,
    @Schema(nullable = true) BigDecimal landAreaSqm,
    @Schema(nullable = true) BigDecimal grossFloorAreaSqm,
    @Schema(nullable = true) LocalDate moveInDate,
    boolean moveInNegotiable,
    @Schema(nullable = true) Long maintenanceFeeAmount,
    @Schema(nullable = true) String maintenanceFeeItems,
    String occupancyStatus,
    @Schema(nullable = true) String description,
    @Schema(nullable = true) String memo,
    @Schema(nullable = true) LocalDate referenceLeaseEndDate,
    @Schema(nullable = true) String referenceLeaseEndSource,
    List<PhotoItem> photos,
    List<PropertyDetailResponse.CustomFieldItem> customFields,
    List<PropertyDetailResponse.OfferItem> offers,
    @Schema(nullable = true) LocalDate leaseEndDate,
    @Schema(nullable = true) String leaseEndSource,
    @Schema(nullable = true) OffsetDateTime deletedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long officeId,
    Long version) {

  public record PhotoItem(
      Long photoId, String objectKey, @Schema(nullable = true) String description, int sortOrder) {}
}
