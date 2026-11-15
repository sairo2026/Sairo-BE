package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "미리보기. 어떤 데이터도 생성하지 않는다.")
public record ImportPreviewResponse(
    List<PreviewRow> rows,
    List<DuplicateWarning> duplicateWarnings,
    Summary summary,
    int page,
    int size,
    long totalElements) {

  public record PreviewRow(
      int sourceRowNumber,
      Map<String, String> sourceValues,
      Map<String, Object> normalizedValues,
      List<UnitConversion> unitConversions,
      List<RelationMatch> relationMatches,
      List<Warning> warnings,
      @Schema(
              description =
                  "NEW, ALREADY_IMPORTED, DUPLICATE_IN_FILE, DUPLICATE_EXISTING 중 하나다."
                      + " NEW가 아니면 실행할 때 새로 만들지 않고 제외한다.")
          String duplicateJudgement,
      @Schema(nullable = true) Long matchedTargetId) {}

  public record UnitConversion(
      String field, String sourceValue, Object targetValue, String ruleCode) {}

  public record RelationMatch(
      String sourceField,
      String targetType,
      @Schema(nullable = true) Long targetId,
      double confidence) {}

  public record Warning(
      String code, @Schema(nullable = true) String field, String message, String severity) {}

  public record DuplicateWarning(
      int sourceRowNumber,
      String sourceSystem,
      String sourceType,
      String sourceId,
      long existingTargetId,
      @Schema(description = "ALREADY_IMPORTED 또는 DUPLICATE_EXISTING") String reason,
      boolean existingArchived) {}

  public record Summary(
      int rowCount,
      int errorRowCount,
      int warningCount,
      @Schema(description = "새로 만들지 않고 제외할 줄 수다. 아래 세 가지 제외 건수의 합이다.") int duplicateCount,
      int relationMatchCount,
      @Schema(description = "오류 없이 새로 만들 줄 수다.") int newCount,
      int alreadyImportedCount,
      int duplicateInFileCount,
      int duplicateExistingCount) {}
}
