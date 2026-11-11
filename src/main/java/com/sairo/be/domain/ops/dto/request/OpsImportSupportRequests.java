package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 유료 이전 지원 운영 처리 요청 본문이다(OPS-07). */
public final class OpsImportSupportRequests {

  private OpsImportSupportRequests() {}

  @Schema(description = "이전 지원 견적 제시. 표준 견적은 VAT 포함 220,000원이다")
  public record ImportSupportQuoteRequest(
      @Schema(description = "견적 금액(원, VAT 포함)") @NotNull @Positive @Max(100_000_000L) Long quoteAmount,
      @Schema(description = "범위 비고(선택)") @PlainText(min = 0, max = 500) String scopeNote) {}

  @Schema(description = "견적 수락 전 이전 지원 신청 거절")
  public record ImportSupportRejectRequest(
      @Schema(description = "거절 사유. 대표에게 공개한다") @PlainText(max = 1000) String reason) {}
}
