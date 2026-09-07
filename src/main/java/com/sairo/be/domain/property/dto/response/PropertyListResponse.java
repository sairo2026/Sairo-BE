package com.sairo.be.domain.property.dto.response;

import com.sairo.be.domain.property.entity.PropertyDealType;
import java.util.List;

public record PropertyListResponse(List<PropertyItem> properties) {

  public record PropertyItem(
      Long propertyId,
      String propertyName,
      String address,
      String addressDetail,
      PropertyDealType dealType) {}
}
