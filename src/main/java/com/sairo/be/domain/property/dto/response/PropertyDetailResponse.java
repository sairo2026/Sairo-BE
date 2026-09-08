package com.sairo.be.domain.property.dto.response;

import com.sairo.be.domain.property.entity.PropertyDealType;
import java.time.Instant;

public record PropertyDetailResponse(
    Long propertyId,
    String address,
    String addressDetail,
    String propertyName,
    PropertyDealType dealType,
    Instant createdAt) {}
