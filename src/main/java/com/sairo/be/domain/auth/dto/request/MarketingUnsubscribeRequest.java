package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "광고성 이메일 수신 거부 요청")
public record MarketingUnsubscribeRequest(
    @Schema(description = "수신 거부 링크의 서명된 토큰") @NotBlank @Size(max = 500) String token) {}
