package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "이메일 인증 완료 응답")
public record EmailVerifyResponse(
    @Schema(description = "이메일 인증 완료를 증명하는 서명된 티켓") String verifiedEmailTicket) {}
