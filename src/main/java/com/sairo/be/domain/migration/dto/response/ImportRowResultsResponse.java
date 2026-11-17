package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "이전 실행의 줄별 결과. 성공·실패·제외를 나누어 어느 줄이 왜 그렇게 됐는지 돌려준다.")
public record ImportRowResultsResponse(
    List<Row> rows, Summary summary, int page, int size, long totalElements) {

  public record Summary(long succeeded, long failed, long skipped) {}

  @Schema(
      description = "outcome은 SUCCEEDED, FAILED, SKIPPED 중 하나다. rowNumber는 파일의 줄 번호이며 제목 줄이 1이다.")
  public record Row(
      int rowNumber,
      String outcome,
      @Schema(nullable = true, description = "고객명 또는 매물명") String label,
      @Schema(nullable = true) String sourceId,
      @Schema(description = "실패한 줄의 원인과 고치는 방법. 실패가 아니면 빈 배열이다.") List<Issue> issues,
      @Schema(
              nullable = true,
              description = "ALREADY_IMPORTED, DUPLICATE_IN_FILE, DUPLICATE_EXISTING")
          String skipReason,
      @Schema(nullable = true, description = "만들어졌거나 이미 있던 고객·매물 번호") Long targetId,
      @Schema(description = "실패한 줄을 앱에서 고칠 수 있는 항목과 현재 값. 실패가 아니면 빈 배열이다.") List<FieldValue> fields,
      @Schema(description = "고친 값이 저장돼 있어 다시 처리하면 성공할 수 있는 줄이다.") boolean readyToRetry) {}

  public record Issue(
      String code,
      @Schema(nullable = true) String field,
      @Schema(nullable = true) String fieldLabel,
      String message,
      String fix,
      boolean correctable) {}

  public record FieldValue(
      String field, String label, @Schema(nullable = true) String value, boolean corrected) {}
}
