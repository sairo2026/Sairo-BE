package com.sairo.be.domain.billing.mapper;

import com.sairo.be.domain.billing.dto.response.BillingInvoiceDetailResponse;
import com.sairo.be.domain.billing.dto.response.BillingInvoiceItemResponse;
import com.sairo.be.domain.billing.dto.response.BillingPlanListResponse;
import com.sairo.be.domain.billing.dto.response.PublicPricingResponse;
import com.sairo.be.domain.billing.dto.response.RefundRequestItemResponse;
import com.sairo.be.domain.billing.dto.response.RefundRequestStatusResponse;
import com.sairo.be.domain.billing.entity.BillingChargeAttempt;
import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.RefundRequest;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.PriceScheduleRow;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.RefundSums;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.SupplierRow;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.TaxDocumentRow;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.TrialEventRow;
import com.sairo.be.domain.billing.service.PriceCalculator;
import com.sairo.be.domain.billing.service.PriceCalculator.PriceTable;
import com.sairo.be.domain.billing.service.PriceScheduleSelector.PriceSelection;
import com.sairo.be.global.support.TimeRules;
import java.util.ArrayList;
import java.util.List;

public final class BillingMapper {

  private static final int EXAMPLE_MAX_MEMBERS = 10;
  private static final int GENERAL_TRIAL_DAYS = 60;

  private BillingMapper() {}

  public static PublicPricingResponse toPublicPricing(
      PriceScheduleRow price,
      TrialEventRow trial,
      String refundPolicyVersion,
      String termsUrl,
      String refundUrl,
      String privacyUrl,
      String imageUrl,
      boolean provisionalPrice) {
    PriceTable table =
        new PriceTable(price.soloAmount(), price.teamFirstExtra(), price.teamAfterFourExtra());
    String image = imageUrl == null || imageUrl.isBlank() ? null : imageUrl;
    List<PublicPricingResponse.PlanTier> plans =
        List.of(
            new PublicPricingResponse.PlanTier("SOLO", 1, 1, table.soloAmount(), 1, null, image),
            new PublicPricingResponse.PlanTier(
                "TEAM",
                2,
                PriceCalculator.TEAM_FIRST_TIER_MAX,
                table.soloAmount(),
                1,
                table.teamFirstExtra(),
                image),
            new PublicPricingResponse.PlanTier(
                "TEAM",
                PriceCalculator.TEAM_FIRST_TIER_MAX + 1,
                null,
                PriceCalculator.teamUpperTierBase(table),
                PriceCalculator.TEAM_FIRST_TIER_MAX,
                table.teamAfterFourExtra(),
                image));
    List<PublicPricingResponse.Example> examples = new ArrayList<>();
    for (int members = 1; members <= EXAMPLE_MAX_MEMBERS; members++) {
      examples.add(
          new PublicPricingResponse.Example(
              members, PriceCalculator.monthlyAmount(table, members)));
    }
    return new PublicPricingResponse(
        price.version(),
        TimeRules.toDisplayOffset(price.effectiveAt()),
        "KRW",
        true,
        "MONTHLY_PREPAID",
        true,
        plans,
        examples,
        new PublicPricingResponse.Enterprise(true, false),
        refundPolicyVersion,
        toGeneralTrial(trial),
        new PublicPricingResponse.Links(termsUrl, refundUrl, privacyUrl),
        provisionalPrice);
  }

  private static PublicPricingResponse.GeneralTrial toGeneralTrial(TrialEventRow trial) {
    if (trial == null) {
      return null;
    }
    boolean duration = "DURATION".equals(trial.freeEndMode());
    return new PublicPricingResponse.GeneralTrial(
        trial.freeEndMode(),
        duration ? GENERAL_TRIAL_DAYS : null,
        TimeRules.toDisplayOffset(trial.fixedFreeEndsAt()),
        TimeRules.toDisplayOffset(trial.endsAt()));
  }

  public static BillingPlanListResponse toPlanList(PriceSelection selection, int memberCount) {
    PriceTable table = selection.table();
    BillingPlanListResponse.Plan solo =
        new BillingPlanListResponse.Plan(
            "SOLO",
            table.soloAmount(),
            null,
            List.of(new BillingPlanListResponse.Tier(1, 1, table.soloAmount(), 1, null)),
            table.soloAmount());
    BillingPlanListResponse.Plan team =
        new BillingPlanListResponse.Plan(
            "TEAM",
            table.soloAmount(),
            table.teamFirstExtra(),
            List.of(
                new BillingPlanListResponse.Tier(
                    2,
                    PriceCalculator.TEAM_FIRST_TIER_MAX,
                    table.soloAmount(),
                    1,
                    table.teamFirstExtra()),
                new BillingPlanListResponse.Tier(
                    PriceCalculator.TEAM_FIRST_TIER_MAX + 1,
                    null,
                    PriceCalculator.teamUpperTierBase(table),
                    PriceCalculator.TEAM_FIRST_TIER_MAX,
                    table.teamAfterFourExtra())),
            PriceCalculator.monthlyAmount(table, memberCount));
    BillingPlanListResponse.Plan enterprise =
        new BillingPlanListResponse.Plan("ENTERPRISE", null, null, List.of(), null);
    return new BillingPlanListResponse(
        selection.version(), selection.kind(), true, memberCount, List.of(solo, team, enterprise));
  }

  public static BillingInvoiceItemResponse toInvoiceItem(BillingInvoice invoice, RefundSums sums) {
    long refunded = sums == null ? 0 : sums.refunded();
    long reserved = sums == null ? 0 : sums.reserved();
    return new BillingInvoiceItemResponse(
        invoice.getId(),
        TimeRules.toDisplayOffset(invoice.getBillingPeriodStart()),
        TimeRules.toDisplayOffset(invoice.getBillingPeriodEnd()),
        invoice.getAmount(),
        invoice.getMemberCountSnapshot(),
        invoice.getConsentAmountLimit(),
        invoice.getStatus().name(),
        refunded,
        refundableAmount(invoice, refunded, reserved));
  }

  public static long refundableAmount(BillingInvoice invoice, long refunded, long reserved) {
    if (!invoice.isRefundableStatus()) {
      return 0;
    }
    return Math.max(0, invoice.getAmount() - refunded - reserved);
  }

  public static BillingInvoiceDetailResponse toInvoiceDetail(
      BillingInvoice invoice,
      RefundSums sums,
      List<BillingChargeAttempt> attempts,
      SupplierRow supplier,
      List<TaxDocumentRow> taxDocuments) {
    long refunded = sums == null ? 0 : sums.refunded();
    long reserved = sums == null ? 0 : sums.reserved();
    return new BillingInvoiceDetailResponse(
        invoice.getId(),
        TimeRules.toDisplayOffset(invoice.getBillingPeriodStart()),
        TimeRules.toDisplayOffset(invoice.getBillingPeriodEnd()),
        invoice.getAmount(),
        invoice.getSupplyAmount(),
        invoice.getVatAmount(),
        invoice.getVatRateBpSnapshot(),
        invoice.getMemberCountSnapshot(),
        invoice.getConsentAmountLimit(),
        invoice.getStatus().name(),
        refunded,
        refundableAmount(invoice, refunded, reserved),
        TimeRules.toDisplayOffset(invoice.getNextRetryAt()),
        attempts.stream()
            .map(
                attempt ->
                    new BillingInvoiceDetailResponse.Attempt(
                        attempt.getPgOrderId(),
                        attempt.getPgTransactionId(),
                        attempt.getResult().name(),
                        attempt.getFailureCategory() == null
                            ? null
                            : attempt.getFailureCategory().name(),
                        TimeRules.toDisplayOffset(attempt.getAttemptedAt())))
            .toList(),
        supplier == null
            ? null
            : new BillingInvoiceDetailResponse.Supplier(
                supplier.businessName(),
                supplier.representativeName(),
                supplier.businessNumber(),
                supplier.address(),
                supplier.versionNo()),
        taxDocuments.stream()
            .map(
                document ->
                    new BillingInvoiceDetailResponse.TaxDocument(
                        document.id(),
                        document.documentKind(),
                        document.status(),
                        document.issuedOn(),
                        maskEmail(document.receiverEmail()),
                        document.supplyAmount(),
                        document.vatAmount()))
            .toList());
  }

  public static String maskEmail(String email) {
    if (email == null) {
      return null;
    }
    int at = email.indexOf('@');
    if (at < 1) {
      return "***";
    }
    int visible = Math.min(2, at);
    return email.substring(0, visible) + "***" + email.substring(at);
  }

  public static RefundRequestItemResponse toRefundItem(RefundRequest request) {
    return new RefundRequestItemResponse(
        request.getId(),
        request.getInvoiceId(),
        request.getStatus().name(),
        request.getReasonCode().name(),
        request.getRefundAmount(),
        TimeRules.toDisplayOffset(request.getRequestedAt()));
  }

  public static RefundRequestStatusResponse toRefundStatus(RefundRequest request) {
    RefundRequestStatusResponse.Calculation calculation =
        request.hasCalculation()
            ? new RefundRequestStatusResponse.Calculation(
                request.getOriginalApprovedAmount(),
                request.getTotalPeriodSeconds(),
                request.getRemainingPeriodSeconds(),
                request.getCouponExcludedAmount() == null ? 0 : request.getCouponExcludedAmount(),
                request.getPriorCompensationAmount() == null
                    ? 0
                    : request.getPriorCompensationAmount(),
                TimeRules.toDisplayOffset(request.getEffectiveEndAt()))
            : null;
    return new RefundRequestStatusResponse(
        request.getId(),
        request.getInvoiceId(),
        request.getStatus().name(),
        request.getReasonCode().name(),
        request.getEntitlementAmount(),
        request.getPreviousRefundAmount(),
        request.getReservedAmount(),
        request.getRefundAmount(),
        request.getRejectionReason(),
        request.getFailureReason(),
        calculation,
        TimeRules.toDisplayOffset(request.getRequestedAt()));
  }
}
