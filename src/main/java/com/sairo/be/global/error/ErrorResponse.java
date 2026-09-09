package com.sairo.be.global.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "요청 경로·검증 실패 등 컨트롤러 계층에서 발생하는 오류 응답이다.")
public record ErrorResponse(
    @Schema(description = "오류가 발생한 요청 경로", example = "/api/properties/999999") String instance,
    @Schema(description = "HTTP 상태 코드", example = "404") int status,
    @Schema(description = "HTTP 상태 코드의 영문 설명", example = "Not Found") String title,
    @Schema(description = "오류 코드", example = "RESOURCE_NOT_FOUND") String code,
    @Schema(description = "사용자에게 보여줄 한글 오류 메시지", example = "요청한 리소스를 찾을 수 없습니다.") String message,
    @Schema(description = "오류 추적용 UUID") String traceId,
    @Schema(description = "요청 바디 검증에 실패했을 때만 포함되는 필드별 오류 목록") List<FieldErrorItem> fieldErrors) {

  @Schema(description = "검증에 실패한 필드 하나의 오류")
  public record FieldErrorItem(
      @Schema(description = "검증에 실패한 요청 필드명", example = "address") String field,
      @Schema(description = "필드 검증 실패 사유", example = "must not be blank") String message) {}
}
