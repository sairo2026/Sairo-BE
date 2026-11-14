package com.sairo.be.domain.customer.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CandidatePropertyResponse(List<Item> items) {

  public record Item(
      Long propertyId,
      String name,
      String dealType,
      @Schema(nullable = true) Long depositAmount,
      @Schema(nullable = true) Long salePrice,
      @Schema(nullable = true) Long monthlyRentAmount) {}
}
