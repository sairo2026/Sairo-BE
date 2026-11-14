package com.sairo.be.domain.property.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record PropertyDuplicateCheckResponse(List<Match> matches) {

  public record Match(
      Long propertyId,
      @Schema(nullable = true) String address,
      @Schema(nullable = true) String addressDetail,
      String categoryName,
      String registrationStage) {}
}
