package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "이메일 로그인수단 연결 완료 요청")
public record LinkEmailRequest(
    @Schema(description = "연결용 이메일 인증 티켓") @NotBlank String verifiedEmailTicket,
    @Schema(description = "새 이메일 로그인용 비밀번호. 코드 포인트 기준 8~12자") @NotBlank String password) {}
