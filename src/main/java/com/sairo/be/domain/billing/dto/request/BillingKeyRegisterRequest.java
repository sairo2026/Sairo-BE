package com.sairo.be.domain.billing.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "카드 등록창 성공 리다이렉트 값")
public record BillingKeyRegisterRequest(
    @Schema(description = "토스 자동결제 등록 인증 키") @NotBlank @Size(max = 300) String authKey,
    @Schema(description = "카드 등록 세션에서 발급한 고객 키") @NotBlank @Size(max = 100) String customerKey,
    @Schema(description = "확인한 카드 등록 안내 버전") @NotBlank @Size(max = 20) String cardNoticeVersion) {}
