package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "구독 구매 동의 화면에 보여줄 값. 서버가 현재 사무소·인원·가격표로 계산한다.")
public record PurchaseConsentPreviewResponse(
    String termsVersion,
    String plan,
    int memberCount,
    String priceVersion,
    Long priceChangeNoticeId,
    long totalAmountInclVat,
    long vatAmount,
    long amountLimit,
    OffsetDateTime periodStart,
    OffsetDateTime periodEnd,
    OffsetDateTime nextChargeAt,
    OffsetDateTime firstBillingScheduledAt,
    boolean autoRenewal,
    String cancellationTerms,
    String refundTerms) {}
