package com.sairo.be.global.error;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "검증에 실패한 필드 하나의 오류")
public record FieldErrorItem(
    @Schema(description = "검증에 실패한 요청 필드 위치", example = "address") String field,
    @Schema(description = "필드 오류 코드", example = "REQUIRED") FieldErrorCode code,
    @Schema(description = "필드 오류 설명", example = "필수 입력입니다.") String message) {}
