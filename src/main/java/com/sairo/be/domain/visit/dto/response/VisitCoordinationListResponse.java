package com.sairo.be.domain.visit.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record VisitCoordinationListResponse(
    Map<String, Long> statusCounts, List<Item> items, int page, int size, long totalElements) {

  public record Item(
      Long coordinationId,
      String customerName,
      int candidatePropertyCount,
      String managerName,
      LocalDate periodStartDate,
      LocalDate periodEndDate,
      List<Candidate> candidateProperties,
      String status,
      Long managerMembershipId,
      OffsetDateTime createdAt,
      Long officeId,
      OffsetDateTime updatedAt,
      Long version) {}

  public record Candidate(Long candidatePropertyId, String propertyName) {}
}
