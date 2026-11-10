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
      List<Warning> warnings) {}

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
      long existingTargetId) {}

  public record Summary(
      int rowCount,
      int errorRowCount,
      int warningCount,
      int duplicateCount,
      int relationMatchCount) {}
}
