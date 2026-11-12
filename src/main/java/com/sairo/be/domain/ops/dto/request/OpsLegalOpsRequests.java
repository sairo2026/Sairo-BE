package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 보관 기간 규칙 요청 본문이다(OPS-19). */
public final class OpsLegalOpsRequests {

  private OpsLegalOpsRequests() {}

  @Schema(description = "보관 기간 규칙 수정. If-Match 헤더로 현재 version을 보낸다")
  public record RetentionRuleUpdateRequest(
      @Schema(description = "보관 일수") @NotNull @Min(1) @Max(36_500) Integer retentionDays,
      @Schema(description = "근거 메모. 근거 조항을 확인하지 못했으면 검토 대상 법령 이름만 적는다") @PlainText(max = 2_000)
          String basisNote,
      @Schema(description = "ASSUMPTION, LAWYER_CONFIRMED, ADVISOR_CONFIRMED") @NotNull String status,
      @Schema(description = "변경 사유. 운영 감사 기록에 남는다") @PlainText(max = 1_000) String changeReason) {}
}
