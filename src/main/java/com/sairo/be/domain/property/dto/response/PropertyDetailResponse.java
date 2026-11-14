package com.sairo.be.domain.property.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "매물 상세")
public record PropertyDetailResponse(
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
    List<CustomFieldItem> customFields,
    List<OfferItem> offers,
    List<ArchivedCustomFieldItem> archivedCustomFields,
    List<ActiveCoordinationItem> activeCoordinations,
    List<OwnerOccupantItem> ownerOccupants,
    List<VisitItem> visits,
    List<ContractItem> contracts,
    @Schema(nullable = true) LocalDate leaseEndDate,
    @Schema(nullable = true) String leaseEndSource,
    @Schema(nullable = true) OffsetDateTime deletedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long officeId,
    Long version) {

  public record PhotoItem(
      Long photoId,
      String objectKey,
      @Schema(nullable = true) String description,
      int sortOrder,
      String url,
      OffsetDateTime urlExpiresAt) {}

  public record CustomFieldItem(
      Long fieldDefinitionId,
      String fieldName,
      String fieldType,
      @Schema(nullable = true) Object value) {}

  public record OfferItem(
      Long offerId,
      String dealType,
      String status,
      @Schema(nullable = true) Long salePrice,
      @Schema(nullable = true) Long depositAmount,
      @Schema(nullable = true) Long monthlyRentAmount,
      @Schema(nullable = true) Long premiumAmount) {}

  public record ArchivedCustomFieldItem(
      Long fieldDefinitionId,
      String fieldName,
      String fieldType,
      @Schema(nullable = true) Object value,
      Long previousCategoryId,
      String previousCategoryName) {}

  public record ActiveCoordinationItem(Long coordinationId, String status, String customerName) {}

  public record OwnerOccupantItem(
      Long linkId,
      Long customerId,
      String customerName,
      String role,
      LocalDate startDate,
      @Schema(nullable = true) LocalDate endDate) {}

  public record VisitItem(
      Long scheduleItemId,
      Long coordinationId,
      Long customerId,
      String customerName,
      @Schema(nullable = true) OffsetDateTime scheduledStartAt,
      String result,
      @Schema(nullable = true) OffsetDateTime actualVisitedAt) {}

  public record ContractItem(
      Long contractId,
      String dealType,
      String contractStatus,
      @Schema(nullable = true) LocalDate signedDate,
      @Schema(nullable = true) LocalDate leaseStartDate,
      @Schema(nullable = true) LocalDate leaseEndDate) {}
}
