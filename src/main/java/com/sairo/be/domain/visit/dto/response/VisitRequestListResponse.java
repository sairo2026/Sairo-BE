package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record VisitRequestListResponse(List<Item> items) {

  public record Item(
      Long requestId,
      Long propertyId,
      Long propertyOfficeId,
      boolean crossOffice,
      Long accessGrantId,
      Long candidatePropertyId,
      String status,
      OffsetDateTime requestedStartAt,
      OffsetDateTime requestedEndAt,
      OffsetDateTime submittedAt,
      OffsetDateTime closedAt,
      String statusReason,
      long version) {}
}
