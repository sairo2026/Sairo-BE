package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record ApproverConditionImpactPreviewResponse(
    boolean hasImpact, List<AffectedItem> affectedItems) {

  public record AffectedItem(
      Long scheduleItemId,
      OffsetDateTime scheduledStartAt,
      OffsetDateTime scheduledEndAt,
      List<String> violatedRules) {}
}
