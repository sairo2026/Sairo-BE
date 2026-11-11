package com.sairo.be.domain.visit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public record ApproverSelectionResponse(
    Long selectionId,
    Long requestId,
    String requestStatus,
    Revalidation revalidation,
    List<Long> timeClosedRequestIds) {

  public record Revalidation(
      String result,
      @Schema(nullable = true) String failureCode,
      @Schema(nullable = true) String failureDetail,
      OffsetDateTime checkedAt) {}
}
