package com.sairo.be.domain.property.dto.request;

import com.sairo.be.domain.property.entity.PropertyDealType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PropertyCreateRequest(
    @NotBlank String address,
    String addressDetail,
    String propertyName,
    @NotNull PropertyDealType dealType) {}
