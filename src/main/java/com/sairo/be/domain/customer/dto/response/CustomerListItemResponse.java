package com.sairo.be.domain.customer.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public record CustomerListItemResponse(
    Long customerId,
    String name,
    boolean isCorporation,
    @Schema(nullable = true) String phone,
    List<String> roles,
    @Schema(nullable = true) OffsetDateTime deletedAt,
    OffsetDateTime updatedAt,
    Long officeId,
    OffsetDateTime createdAt,
    Long version) {}
