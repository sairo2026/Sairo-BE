package com.sairo.be.domain.visit.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

public record VisitManagerUpdateResponse(
    @JsonUnwrapped VisitCoordinationDetailResponse detail, List<AffectedItem> affectedItems) {

  public record AffectedItem(
      Long scheduleItemId, Long candidatePropertyId, String state, String reason) {}
}
