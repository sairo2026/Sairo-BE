package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record VisitCoordinationCreateResponse(
    Long coordinationId,
    String status,
    List<Candidate> candidateProperties,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {

  public record Candidate(Long candidatePropertyId, Long propertyId, int sortOrder) {}
}
