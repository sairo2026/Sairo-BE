package com.sairo.be.domain.property.mapper;

import com.sairo.be.domain.property.dto.response.PropertyCategoryListResponse;
import com.sairo.be.domain.property.dto.response.PropertyCategoryResponse;
import com.sairo.be.domain.property.dto.response.PropertyCreateResponse;
import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
import com.sairo.be.domain.property.dto.response.PropertyFieldDefinitionListResponse;
import com.sairo.be.domain.property.dto.response.PropertyFieldDefinitionResponse;
import com.sairo.be.domain.property.dto.response.PropertyListItemResponse;
import com.sairo.be.domain.property.dto.response.PropertyPhotoResponse;
import com.sairo.be.domain.property.dto.response.PropertyUpdateResponse;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.entity.PropertyCategory;
import com.sairo.be.domain.property.entity.PropertyCustomFieldDefinition;
import com.sairo.be.domain.property.entity.PropertyPhoto;
import com.sairo.be.domain.property.repository.PropertyReadRepository;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.time.Instant;
import java.util.List;

public final class PropertyMapper {

  private static final String LEASE_SOURCE_CONTRACT = "CONTRACT";
  private static final String LEASE_SOURCE_REFERENCE = "REFERENCE";

  private PropertyMapper() {}

  public static PropertyCreateResponse toCreateResponse(Property property) {
    return new PropertyCreateResponse(
        property.getId(),
        property.getRegistrationStage().name(),
        property.getOfficeId(),
        TimeRules.toDisplayOffset(property.getCreatedAt()),
        TimeRules.toDisplayOffset(property.getUpdatedAt()),
        property.getVersion());
  }

  public static PropertyListItemResponse toListItem(
      PropertyReadRepository.ListRow row,
      List<String> ownerNames,
      List<String> occupantNames,
      List<PropertyReadRepository.OfferRow> activeOffers,
      java.time.LocalDate contractLeaseEnd) {
    String leaseSource = null;
    java.time.LocalDate leaseEnd = null;
    if (contractLeaseEnd != null) {
      leaseEnd = contractLeaseEnd;
      leaseSource = LEASE_SOURCE_CONTRACT;
    } else if (row.referenceLeaseEndDate() != null) {
      leaseEnd = row.referenceLeaseEndDate();
      leaseSource = LEASE_SOURCE_REFERENCE;
    }
    return new PropertyListItemResponse(
        row.propertyId(),
        row.name(),
        row.address(),
        row.addressDetail(),
        row.categoryId(),
        row.categoryName(),
        row.registrationStage(),
        row.managingMembershipId(),
        row.managingMembershipId() == null
            ? null
            : WithdrawnMemberDisplay.name(
                row.managerWithdrawn(), row.managerName(), row.managerAliasNo()),
        row.occupancyStatus(),
        ownerNames,
        occupantNames,
        leaseEnd,
        leaseSource,
        row.memo(),
        activeOffers.stream()
            .map(
                offer ->
                    new PropertyListItemResponse.ActiveOfferSummary(
                        offer.dealType(),
                        offer.status(),
                        offer.salePrice(),
                        offer.depositAmount(),
                        offer.monthlyRentAmount(),
                        offer.premiumAmount()))
            .toList(),
        TimeRules.toDisplayOffset(row.updatedAt()),
        row.officeId(),
        TimeRules.toDisplayOffset(row.createdAt()),
        row.version());
  }

  public static PropertyDetailResponse toDetail(PropertyView view) {
    Property p = view.property();
    PropertyView.Linked linked = view.linked();
    return new PropertyDetailResponse(
        p.getId(),
        p.getName(),
        p.getAddress(),
        p.getAddressDetail(),
        p.getCategoryId(),
        view.categoryName(),
        p.getManagingMembershipId(),
        view.managingMemberName(),
        p.getRegistrationStage().name(),
        p.getExclusiveAreaSqm(),
        p.getSupplyAreaSqm(),
        p.getLandAreaSqm(),
        p.getGrossFloorAreaSqm(),
        p.getMoveInDate(),
        p.isMoveInNegotiable(),
        p.getMaintenanceFeeAmount(),
        p.getMaintenanceFeeItems(),
        p.getOccupancyStatus().name(),
        p.getDescription(),
        p.getMemo(),
        p.getReferenceLeaseEndDate(),
        p.getReferenceLeaseEndSource(),
        view.photos().stream()
            .map(
                photo ->
                    new PropertyDetailResponse.PhotoItem(
                        photo.getId(),
                        photo.getObjectKey(),
                        photo.getDescription(),
                        photo.getSortOrder(),
                        view.photoUrls().get(photo.getId()),
                        TimeRules.toDisplayOffset(view.photoUrlExpiresAt())))
            .toList(),
        customFields(view.fields()),
        offers(view.offers()),
        view.archivedFields().stream()
            .map(
                field ->
                    new PropertyDetailResponse.ArchivedCustomFieldItem(
                        field.definition().getId(),
                        field.definition().getFieldName(),
                        field.definition().getFieldType().name(),
                        field.value(),
                        field.previousCategoryId(),
                        field.previousCategoryName()))
            .toList(),
        linked.activeCoordinations().stream()
            .map(
                row ->
                    new PropertyDetailResponse.ActiveCoordinationItem(
                        row.coordinationId(), row.status(), row.customerName()))
            .toList(),
        linked.ownerOccupants().stream()
            .map(
                row ->
                    new PropertyDetailResponse.OwnerOccupantItem(
                        row.linkId(),
                        row.customerId(),
                        row.customerName(),
                        row.role(),
                        row.startDate(),
                        row.endDate()))
            .toList(),
        linked.visits().stream()
            .map(
                row ->
                    new PropertyDetailResponse.VisitItem(
                        row.scheduleItemId(),
                        row.coordinationId(),
                        row.customerId(),
                        row.customerName(),
                        TimeRules.toDisplayOffset(row.scheduledStartAt()),
                        row.result(),
                        TimeRules.toDisplayOffset(row.actualVisitedAt())))
            .toList(),
        linked.contracts().stream()
            .map(
                row ->
                    new PropertyDetailResponse.ContractItem(
                        row.contractId(),
                        row.dealType(),
                        row.contractStatus(),
                        row.signedDate(),
                        row.leaseStartDate(),
                        row.leaseEndDate()))
            .toList(),
        view.leaseEndDate(),
        view.leaseEndSource(),
        TimeRules.toDisplayOffset(p.getDeletedAt()),
        TimeRules.toDisplayOffset(p.getCreatedAt()),
        TimeRules.toDisplayOffset(p.getUpdatedAt()),
        p.getOfficeId(),
        p.getVersion());
  }

  public static PropertyUpdateResponse toUpdate(PropertyView view) {
    Property p = view.property();
    return new PropertyUpdateResponse(
        p.getId(),
        p.getName(),
        p.getAddress(),
        p.getAddressDetail(),
        p.getCategoryId(),
        view.categoryName(),
        p.getManagingMembershipId(),
        view.managingMemberName(),
        p.getRegistrationStage().name(),
        p.getExclusiveAreaSqm(),
        p.getSupplyAreaSqm(),
        p.getLandAreaSqm(),
        p.getGrossFloorAreaSqm(),
        p.getMoveInDate(),
        p.isMoveInNegotiable(),
        p.getMaintenanceFeeAmount(),
        p.getMaintenanceFeeItems(),
        p.getOccupancyStatus().name(),
        p.getDescription(),
        p.getMemo(),
        p.getReferenceLeaseEndDate(),
        p.getReferenceLeaseEndSource(),
        view.photos().stream()
            .map(
                photo ->
                    new PropertyUpdateResponse.PhotoItem(
                        photo.getId(),
                        photo.getObjectKey(),
                        photo.getDescription(),
                        photo.getSortOrder()))
            .toList(),
        customFields(view.fields()),
        offers(view.offers()),
        view.leaseEndDate(),
        view.leaseEndSource(),
        TimeRules.toDisplayOffset(p.getDeletedAt()),
        TimeRules.toDisplayOffset(p.getCreatedAt()),
        TimeRules.toDisplayOffset(p.getUpdatedAt()),
        p.getOfficeId(),
        p.getVersion());
  }

  private static List<PropertyDetailResponse.CustomFieldItem> customFields(
      List<PropertyView.FieldView> fields) {
    return fields.stream()
        .map(
            field ->
                new PropertyDetailResponse.CustomFieldItem(
                    field.definition().getId(),
                    field.definition().getFieldName(),
                    field.definition().getFieldType().name(),
                    field.value()))
        .toList();
  }

  private static List<PropertyDetailResponse.OfferItem> offers(
      List<PropertyReadRepository.OfferRow> offers) {
    return offers.stream()
        .map(
            offer ->
                new PropertyDetailResponse.OfferItem(
                    offer.offerId(),
                    offer.dealType(),
                    offer.status(),
                    offer.salePrice(),
                    offer.depositAmount(),
                    offer.monthlyRentAmount(),
                    offer.premiumAmount()))
        .toList();
  }

  public static PropertyPhotoResponse toPhotoResponse(
      PropertyPhoto photo, Property property, String url, Instant urlExpiresAt) {
    return new PropertyPhotoResponse(
        photo.getId(),
        photo.getObjectKey(),
        photo.getDescription(),
        photo.getSortOrder(),
        url,
        TimeRules.toDisplayOffset(urlExpiresAt),
        property.getOfficeId(),
        TimeRules.toDisplayOffset(property.getUpdatedAt()),
        property.getVersion());
  }

  public static PropertyCategoryResponse toCategoryResponse(PropertyCategory category) {
    return new PropertyCategoryResponse(
        category.getId(),
        category.getOfficeId(),
        category.getName(),
        category.isSystem(),
        category.isActive(),
        TimeRules.toDisplayOffset(category.getCreatedAt()));
  }

  public static PropertyCategoryListResponse toCategoryList(List<PropertyCategory> categories) {
    return new PropertyCategoryListResponse(
        categories.stream()
            .map(
                category ->
                    new PropertyCategoryListResponse.Item(
                        category.getId(),
                        category.getName(),
                        category.isSystem(),
                        category.isActive()))
            .toList());
  }

  public static PropertyFieldDefinitionResponse toFieldDefinitionResponse(
      PropertyCustomFieldDefinition definition) {
    return new PropertyFieldDefinitionResponse(
        definition.getId(),
        definition.getCategoryId(),
        definition.getFieldName(),
        definition.getFieldType().name(),
        definition.isActive(),
        TimeRules.toDisplayOffset(definition.getCreatedAt()));
  }

  public static PropertyFieldDefinitionListResponse toFieldDefinitionList(
      List<PropertyCustomFieldDefinition> definitions) {
    return new PropertyFieldDefinitionListResponse(
        definitions.stream()
            .map(
                definition ->
                    new PropertyFieldDefinitionListResponse.Item(
                        definition.getId(),
                        definition.getFieldName(),
                        definition.getFieldType().name(),
                        definition.getNumberKind() == null
                            ? null
                            : definition.getNumberKind().name(),
                        definition.getUnit(),
                        definition.getMinValue(),
                        definition.getMaxValue(),
                        definition.isActive()))
            .toList());
  }
}
