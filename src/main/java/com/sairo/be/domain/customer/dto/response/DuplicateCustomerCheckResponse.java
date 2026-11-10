package com.sairo.be.domain.customer.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public record DuplicateCustomerCheckResponse(List<Item> items) {

  public record Item(
      Long customerId,
      String name,
      boolean isCorporation,
      @Schema(nullable = true) String phone,
      List<String> roles,
      @Schema(nullable = true) OffsetDateTime deletedAt,
      Long managingMembershipId) {}
}
