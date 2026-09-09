package com.sairo.be.global.error;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "세션 인증·CSRF 검증 실패 등 Spring Security 계층에서 발생하는 오류 응답이다.")
public record SecurityErrorResponse(
    @Schema(description = "오류 코드", example = "UNAUTHENTICATED") String code,
    @Schema(description = "사용자에게 보여줄 한글 오류 메시지", example = "로그인이 필요합니다.") String message,
    @Schema(description = "오류 추적용 UUID") String traceId) {}
