package com.sairo.be.domain.property.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PropertyOfferListResponse(List<Item> items) {

  public record Item(
      Long offerId,
      String dealType,
      String status,
      @Schema(nullable = true) Long salePrice,
      @Schema(nullable = true) Long depositAmount,
      @Schema(nullable = true) Long monthlyRentAmount,
      @Schema(nullable = true) Long premiumAmount,
      Long officeId,
      Long version) {}
}
