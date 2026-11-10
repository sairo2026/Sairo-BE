package com.sairo.be.domain.auth.dto.request;

import com.sairo.be.domain.auth.entity.PhoneVerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "휴대폰 인증번호 발송 요청")
public record PhoneVerificationSendRequest(
    @Schema(description = "본인확인을 받을 휴대폰 번호", example = "010-1234-5678") @NotBlank @Size(max = 30) String phone,
    @Schema(description = "인증 목적") @NotNull PhoneVerificationPurpose purpose) {}
