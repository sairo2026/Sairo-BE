package com.sairo.be.domain.customer.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record CustomerAssistantViewResponse(
    Long customerId, String name, @Schema(nullable = true) String phone) {}
