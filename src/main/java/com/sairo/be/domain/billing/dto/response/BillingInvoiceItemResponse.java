package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "청구서 목록 항목")
public record BillingInvoiceItemResponse(
    Long invoiceId,
    OffsetDateTime periodStart,
    OffsetDateTime periodEnd,
    long amount,
    int memberCount,
    long consentAmountLimit,
    String status,
    long refundedAmount,
    long refundableAmount) {}
