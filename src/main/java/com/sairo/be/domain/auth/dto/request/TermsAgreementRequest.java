package com.sairo.be.domain.auth.dto.request;

import com.sairo.be.domain.auth.entity.ConsentPath;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "약관 동의·철회 기록 요청")
public record TermsAgreementRequest(
    @Schema(description = "대상 약관 코드", example = "MARKETING") @NotBlank @Size(max = 30) String termsCode,
    @Schema(description = "동의한 버전", example = "2026-09-01") @NotBlank @Size(max = 20) String version,
    @Schema(description = "동의(참), 철회(거짓)") @NotNull Boolean agreed,
    @Schema(description = "동의 경로. ACCOUNT_SETTINGS 또는 RECONSENT만 보낼 수 있다") @NotNull ConsentPath consentPath) {}
