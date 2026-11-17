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
      @Schema(
              nullable = true,
              description =
                  "재검증에 실패한 이유 코드다. 고객이 낸 가능 시간 밖이면 CUSTOMER_TIME_MISMATCH, 고객의 다른 확정 방문과 겹치면 CUSTOMER_CONFLICT다")
          String failureCode,
      @Schema(nullable = true) String failureDetail,
      OffsetDateTime checkedAt) {}
}
