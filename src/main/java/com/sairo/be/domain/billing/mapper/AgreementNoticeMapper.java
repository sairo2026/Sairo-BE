package com.sairo.be.domain.billing.mapper;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.dto.response.PriceChangeNoticeDeclineResponse;
import com.sairo.be.domain.billing.dto.response.PriceChangeNoticeItemResponse;
import com.sairo.be.domain.billing.dto.response.StartCustomerAgreementResponse;
import com.sairo.be.domain.billing.dto.response.TaxRecipientResponse;
import com.sairo.be.domain.billing.entity.PriceChangeNotice;
import com.sairo.be.domain.billing.entity.StartCustomerAgreement;
import com.sairo.be.domain.billing.entity.TaxInvoiceRecipient;
import com.sairo.be.domain.billing.entity.TaxType;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.OfficeSummaryRow;
import java.time.Instant;

public final class AgreementNoticeMapper {

  private AgreementNoticeMapper() {}

  public static StartCustomerAgreementResponse toAgreement(
      StartCustomerAgreement agreement,
      Instant freeEndsAt,
      String priceVersion,
      int memberCount,
      long monthlyAmount) {
    return new StartCustomerAgreementResponse(
        agreement.getId(),
        agreement.getStatus().name(),
        toDisplayOffset(freeEndsAt),
        priceVersion,
        toDisplayOffset(agreement.getPriceStartsAt()),
        toDisplayOffset(agreement.getPriceEndsAt()),
        new StartCustomerAgreementResponse.MonthlyAmountPreview(memberCount, monthlyAmount),
        new StartCustomerAgreementResponse.Conditions(
            agreement.isWeeklyUseConfirmed(),
            agreement.isMonthlyInterviewConfirmed(),
            agreement.isCasePublicationConsent()),
        agreement.getAgreementTextVersion(),
        agreement.getContractedOn(),
        toDisplayOffset(agreement.getOwnerAcknowledgedAt()),
        agreement.getVersion());
  }

  public static PriceChangeNoticeItemResponse toNoticeItem(
      PriceChangeNotice notice, String fromVersion, String toVersion) {
    return new PriceChangeNoticeItemResponse(
        notice.getId(),
        notice.getChangeKind().name(),
        fromVersion,
        toVersion,
        notice.getMemberCountSnapshot(),
        notice.getCurrentAmountInclVat(),
        notice.getNewAmountInclVat(),
        toDisplayOffset(notice.getNotifiedAt()),
        toDisplayOffset(notice.getEffectiveAt()),
        toDisplayOffset(notice.getAppliesFromPeriodStart()),
        toDisplayOffset(notice.getResponseDueAt()),
        notice.getDecision().name(),
        toDisplayOffset(notice.getDecidedAt()),
        notice.isConsentRequired());
  }

  public static PriceChangeNoticeDeclineResponse toDecline(PriceChangeNotice notice) {
    return new PriceChangeNoticeDeclineResponse(
        notice.getId(),
        notice.getDecision().name(),
        toDisplayOffset(notice.getDecidedAt()),
        toDisplayOffset(notice.getAppliesFromPeriodStart()));
  }

  public static TaxRecipientResponse toRecipient(TaxInvoiceRecipient recipient) {
    return new TaxRecipientResponse(
        true,
        recipient.getId(),
        recipient.getBusinessNumber(),
        recipient.getCompanyName(),
        recipient.getRepresentativeName(),
        recipient.getReceiverEmail(),
        recipient.getTaxType().name(),
        recipient.isIssueRequested(),
        toDisplayOffset(recipient.getRegisteredAt()));
  }

  public static TaxRecipientResponse toRecipientSuggestion(OfficeSummaryRow office) {
    return new TaxRecipientResponse(
        false,
        null,
        office.businessRegistrationNumber(),
        office.name(),
        office.representativeName(),
        null,
        TaxType.UNKNOWN.name(),
        true,
        null);
  }
}
