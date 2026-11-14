package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record VisitScheduleUpdateResponse(
    Long coordinationId,
    String status,
    List<Item> items,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {

  public record Item(
      Long candidatePropertyId,
      int sortOrder,
      OffsetDateTime suggestedStartAt,
      OffsetDateTime suggestedEndAt) {}
}
