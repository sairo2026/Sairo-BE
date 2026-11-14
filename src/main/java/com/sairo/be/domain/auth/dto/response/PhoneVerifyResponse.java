package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "휴대폰 본인확인 완료 응답")
public record PhoneVerifyResponse(
    @Schema(description = "휴대폰 본인확인 증명 티켓. 10분 유효, 1회 사용") String verifiedPhoneTicket,
    @Schema(description = "티켓 만료 시각") OffsetDateTime expiresAt) {}
