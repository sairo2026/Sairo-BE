package com.sairo.be.domain.customer.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record DemandConditionResponse(
    Long conditionId,
    Long customerId,
    String dealType,
    @Schema(nullable = true) Long categoryId,
    @Schema(nullable = true) Long priceMin,
    @Schema(nullable = true) Long priceMax,
    @Schema(nullable = true) Long depositMin,
    @Schema(nullable = true) Long depositMax,
    @Schema(nullable = true) Long rentMin,
    @Schema(nullable = true) Long rentMax,
    @Schema(nullable = true) BigDecimal areaMin,
    @Schema(nullable = true) BigDecimal areaMax,
    @Schema(nullable = true) LocalDate moveInDate,
    List<Region> regions,
    OffsetDateTime createdAt,
    Long officeId,
    OffsetDateTime updatedAt,
    Long version) {

  public record Region(Long regionId, Long demandConditionId, String regionName) {}
}
