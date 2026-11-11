package com.sairo.be.domain.visit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public record ScheduleConflictResponse(List<Conflict> conflicts) {

  public record Conflict(
      Long candidatePropertyId,
      String conflictType,
      Long conflictingScheduleItemId,
      @Schema(nullable = true) Long conflictingCoordinationId,
      OffsetDateTime conflictingStartAt,
      OffsetDateTime conflictingEndAt,
      @Schema(nullable = true) String conflictingPropertyName) {}
}
