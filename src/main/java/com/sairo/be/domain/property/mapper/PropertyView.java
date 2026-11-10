package com.sairo.be.domain.property.mapper;

import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.entity.PropertyCustomFieldDefinition;
import com.sairo.be.domain.property.entity.PropertyPhoto;
import com.sairo.be.domain.property.repository.PropertyReadRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record PropertyView(
    Property property,
    String categoryName,
    String managingMemberName,
    List<PropertyPhoto> photos,
    Map<Long, String> photoUrls,
    Instant photoUrlExpiresAt,
    List<FieldView> fields,
    List<FieldView> archivedFields,
    List<PropertyReadRepository.OfferRow> offers,
    LocalDate leaseEndDate,
    String leaseEndSource,
    Linked linked) {

  public record FieldView(
      PropertyCustomFieldDefinition definition,
      Object value,
      Long previousCategoryId,
      String previousCategoryName) {}

  public record Linked(
      List<PropertyReadRepository.CoordinationRow> activeCoordinations,
      List<PropertyReadRepository.OwnerOccupantRow> ownerOccupants,
      List<PropertyReadRepository.VisitRow> visits,
      List<PropertyReadRepository.ContractRow> contracts) {}
}
