package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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
    String refundTerms,
    @Schema(description = "동의 화면에 보여줄 필수 체크 항목 문구 두 개. 첫째는 자동결제 동의, 둘째는 해지와 환불 조건 확인")
        List<String> checkItems,
    @Schema(description = "동의 저장 오류 코드별 화면 문구") Map<String, String> errorMessages) {}
