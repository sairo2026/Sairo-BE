package com.sairo.be.domain.property.mapper;

import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
import com.sairo.be.domain.property.dto.response.PropertyDuplicateCheckResponse.DuplicateProperty;
import com.sairo.be.domain.property.dto.response.PropertyListResponse.PropertyItem;
import com.sairo.be.domain.property.entity.Property;

public final class PropertyMapper {

  private PropertyMapper() {}

  public static PropertyItem toListItem(Property property) {
    return new PropertyItem(
        property.getId(),
        property.getPropertyName(),
        property.getAddress(),
        property.getAddressDetail(),
        property.getDealType());
  }

  public static DuplicateProperty toDuplicateItem(Property property) {
    return new DuplicateProperty(
        property.getId(),
        property.getAddress(),
        property.getAddressDetail(),
        property.getPropertyName());
  }

  public static PropertyDetailResponse toDetailResponse(Property property) {
    return new PropertyDetailResponse(
        property.getId(),
        property.getAddress(),
        property.getAddressDetail(),
        property.getPropertyName(),
        property.getDealType(),
        property.getCreatedAt());
  }
}
