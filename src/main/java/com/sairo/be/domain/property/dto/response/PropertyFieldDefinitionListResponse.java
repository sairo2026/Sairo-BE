package com.sairo.be.domain.property.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

public record PropertyFieldDefinitionListResponse(List<Item> items) {

  public record Item(
      Long fieldDefinitionId,
      String fieldName,
      String fieldType,
      @Schema(nullable = true) String numberKind,
      @Schema(nullable = true) String unit,
      @Schema(nullable = true) BigDecimal minValue,
      @Schema(nullable = true) BigDecimal maxValue,
      boolean active) {}
}
