package com.sairo.be.domain.coordination.dto.response;

public record HomeSummaryResponse(
    long todayVisitCount,
    long inProgressCoordinationCount,
    long contractExpiringD90Count,
    CoordinationStatusCounts coordinationStatusCounts) {

  public record CoordinationStatusCounts(
      long tenantChecking,
      long buyerDeliveryRequired,
      long buyerChecking,
      long finalConfirmationRequired,
      long scheduleConfirmed,
      long visitCompleted) {}
}
