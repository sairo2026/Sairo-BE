package com.sairo.be.domain.property.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PropertyUpdateRequest(
    @NotBlank String address, String addressDetail, String propertyName) {}
