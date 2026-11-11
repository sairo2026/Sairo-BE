package com.sairo.be.domain.settlement.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "월별 결산 목록이다(MonthlySettlementListResponse). 사무소 생성월부터 현재 달까지 최근 달이 먼저 온다.")
public record MonthlySettlementListResponse(List<Item> items) {

  public record Item(
      String yearMonth,
      @JsonProperty("isFinalized") boolean isFinalized,
      long contractSignedCount) {}
}
