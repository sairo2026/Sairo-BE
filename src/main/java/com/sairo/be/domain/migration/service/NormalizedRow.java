package com.sairo.be.domain.migration.service;

import java.util.List;
import java.util.Map;

public record NormalizedRow(
    Map<String, Object> values,
    List<UnitConversion> unitConversions,
    List<RowWarning> warnings,
    String sourceId) {

  public enum Severity {
    ERROR,
    WARNING
  }

  public record UnitConversion(
      String field, String sourceValue, Object targetValue, String ruleCode) {}

  public record RowWarning(String code, String field, String message, Severity severity) {}

  public boolean hasError() {
    return warnings.stream().anyMatch(warning -> warning.severity() == Severity.ERROR);
  }

  public boolean hasWarning() {
    return warnings.stream().anyMatch(warning -> warning.severity() == Severity.WARNING);
  }

  public String errorSummary() {
    return warnings.stream()
        .filter(warning -> warning.severity() == Severity.ERROR)
        .map(warning -> warning.code() + ": " + warning.message())
        .reduce((left, right) -> left + " / " + right)
        .orElse("");
  }
}
