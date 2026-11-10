package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "AI 헤더 매핑 제안. 참고용이다.")
public record ImportMappingSuggestionResponse(
    List<SourceColumn> sourceColumns,
    List<SuggestedMapping> suggestedMappings,
    List<AvailableTargetField> availableTargetFields) {

  public record SourceColumn(
      String name, List<String> sampleValues, @Schema(nullable = true) String mappedTargetField) {}

  public record SuggestedMapping(String sourceColumn, String targetField, double confidence) {}

  public record AvailableTargetField(String field, String label, String type, boolean required) {}
}
