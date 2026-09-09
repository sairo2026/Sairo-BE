package com.sairo.be.domain.coordination.dto.response;

import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import com.sairo.be.domain.coordination.entity.CustomerResponseResult;
import java.time.Instant;
import java.util.List;

public record CoordinationListResponse(
    StatusCounts statusCounts, List<CoordinationItem> coordinations) {

  public record StatusCounts(
      long tenantChecking,
      long buyerDeliveryRequired,
      long buyerChecking,
      long finalConfirmationRequired,
      long scheduleConfirmed,
      long visitCompleted) {}

  public record CoordinationItem(
      Long coordinationId,
      String propertyAddress,
      String tenantName,
      String tenantPhone,
      Instant visitScheduledAt,
      CoordinationStatus status,
      CustomerResponseResult tenantResult,
      List<CustomerResponseResult> buyerResults) {}
}
