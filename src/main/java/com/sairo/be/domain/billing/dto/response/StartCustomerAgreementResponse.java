package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Schema(description = "승인된 시작 고객 합의 응답")
public record StartCustomerAgreementResponse(
    Long agreementId,
    String status,
    OffsetDateTime freeEndsAt,
    String priceVersion,
    OffsetDateTime priceStartsAt,
    OffsetDateTime priceEndsAt,
    MonthlyAmountPreview monthlyAmountPreview,
    Conditions conditions,
    String agreementTextVersion,
    LocalDate contractedOn,
    OffsetDateTime ownerAcknowledgedAt,
    Long version) {

  public record MonthlyAmountPreview(int memberCount, long amountInclVat) {}

  public record Conditions(
      boolean weeklyUseConfirmed,
      boolean monthlyInterviewConfirmed,
      boolean casePublicationConsent) {}
}
