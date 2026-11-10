package com.sairo.be.domain.auth.dto.request;

import com.sairo.be.domain.auth.entity.PhoneVerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "휴대폰 인증번호 확인 요청")
public record PhoneVerifyRequest(
    @Schema(description = "인증번호를 받은 휴대폰 번호", example = "010-1234-5678") @NotBlank @Size(max = 30) String phone,
    @Schema(description = "발송 때 쓴 인증 목적") @NotNull PhoneVerificationPurpose purpose,
    @Schema(description = "문자로 받은 숫자 6자리", example = "123456") @NotBlank @Pattern(regexp = "\\d{6}") String code) {}
