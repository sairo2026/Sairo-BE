package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "요금제 목록과 현재 과금 인원 기준 예상 금액")
public record BillingPlanListResponse(
    String priceVersion, String priceKind, boolean vatIncluded, int memberCount, List<Plan> plans) {

  public record Plan(
      String planCode, Long basePrice, Long perSeatPrice, List<Tier> tiers, Long estimatedAmount) {}

  public record Tier(
      int minMembers, Integer maxMembers, long basePrice, int includedMembers, Long perSeatPrice) {}
}
