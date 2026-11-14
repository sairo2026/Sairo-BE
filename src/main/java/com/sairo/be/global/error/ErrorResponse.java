package com.sairo.be.global.error;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "모든 오류 응답의 공통 형식이다. 상황에 따라 retryAfterSeconds, actionUrl 같은 선택 필드가 더해진다.")
public record ErrorResponse(
    @Schema(description = "오류 코드", example = "RESOURCE_NOT_FOUND") String code,
    @Schema(description = "사용자에게 보여줄 한글 오류 메시지", example = "요청한 리소스를 찾을 수 없습니다.") String message,
    @Schema(description = "오류 추적용 UUID") String traceId,
    @Schema(description = "400 INVALID_REQUEST에서만 포함되는 필드별 오류 목록")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<FieldErrorItem> fieldErrors,
    @Schema(hidden = true) @JsonAnyGetter Map<String, Object> details) {

  public static ErrorResponse of(ErrorCode errorCode, String message, String traceId) {
    return new ErrorResponse(errorCode.name(), message, traceId, List.of(), Map.of());
  }

  public static ErrorResponse of(BusinessException exception, String traceId) {
    return new ErrorResponse(
        exception.getErrorCode().name(),
        exception.getMessage(),
        traceId,
        exception.getFieldErrors(),
        exception.getDetails());
  }
}
