package com.sairo.be.domain.property.dto.request;

import java.math.BigDecimal;

public record PropertyListRequest(
    String keyword,
    Long categoryId,
    String dealType,
    Long priceMin,
    Long priceMax,
    String areaType,
    BigDecimal areaMin,
    BigDecimal areaMax,
    Long managingMembershipId,
    String registrationStage,
    Boolean archived) {}
