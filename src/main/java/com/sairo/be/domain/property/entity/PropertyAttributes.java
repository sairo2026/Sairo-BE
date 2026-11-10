package com.sairo.be.domain.property.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PropertyAttributes(
    String name,
    String address,
    String addressDetail,
    Long managingMembershipId,
    BigDecimal exclusiveAreaSqm,
    BigDecimal supplyAreaSqm,
    BigDecimal landAreaSqm,
    BigDecimal grossFloorAreaSqm,
    LocalDate moveInDate,
    boolean moveInNegotiable,
    Long maintenanceFeeAmount,
    String maintenanceFeeItems,
    OccupancyStatus occupancyStatus,
    String description,
    String memo,
    LocalDate referenceLeaseEndDate,
    String referenceLeaseEndSource,
    String duplicateOverrideReason) {}
