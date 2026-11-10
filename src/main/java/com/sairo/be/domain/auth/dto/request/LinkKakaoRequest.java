package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "카카오 로그인수단 연결 콜백 요청")
public record LinkKakaoRequest(
    @Schema(description = "카카오 인가 코드") @NotBlank @Size(max = 2000) String code,
    @Schema(description = "인가 시작 때 받은 state") @NotBlank @Size(max = 200) String state) {}
