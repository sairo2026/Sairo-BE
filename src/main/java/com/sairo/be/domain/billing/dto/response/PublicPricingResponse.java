package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "공개 요금 안내 응답")
public record PublicPricingResponse(
    String priceVersion,
    OffsetDateTime effectiveAt,
    String currency,
    boolean vatIncluded,
    String billingCycle,
    boolean autoRenew,
    List<PlanTier> plans,
    List<Example> examples,
    Enterprise enterprise,
    String refundPolicyVersion,
    GeneralTrial generalTrial,
    Links links,
    boolean provisionalPrice) {

  public record PlanTier(
      String planCode,
      int minMembers,
      Integer maxMembers,
      long baseAmount,
      int includedMembers,
      Long perSeatAmount,
      String imageUrl) {}

  public record Example(int members, long monthlyAmount) {}

  public record Enterprise(boolean quotedByInquiry, boolean autoChargeBeforeApproval) {}

  public record GeneralTrial(
      String freeEndMode,
      Integer durationDays,
      OffsetDateTime fixedFreeEndsAt,
      OffsetDateTime applicantWindowEndsAt) {}

  public record Links(String termsUrl, String refundUrl, String privacyUrl) {}
}
