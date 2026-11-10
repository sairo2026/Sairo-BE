package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증 메일·문자 발송 접수 응답")
public record VerificationSendResponse(
    @Schema(description = "발송 접수") boolean accepted,
    @Schema(description = "재발송 대기 초", example = "60") int resendAfterSeconds) {}
