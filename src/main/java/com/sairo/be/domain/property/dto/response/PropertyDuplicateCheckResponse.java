package com.sairo.be.domain.property.dto.response;

import java.util.List;

public record PropertyDuplicateCheckResponse(List<DuplicateProperty> duplicateProperties) {

  public record DuplicateProperty(
      Long propertyId, String address, String addressDetail, String propertyName) {}
}
