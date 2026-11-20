package com.sairo.be.domain.migration.service;

import java.util.List;
import java.util.Map;

public record NormalizedRow(
    Map<String, Object> values,
    List<UnitConversion> unitConversions,
    List<RowWarning> warnings,
    String sourceId,
    boolean autoSourceId) {

  public NormalizedRow(
      Map<String, Object> values,
      List<UnitConversion> unitConversions,
      List<RowWarning> warnings,
      String sourceId) {
    this(values, unitConversions, warnings, sourceId, false);
  }

  /** 시스템이 붙인 번호는 사용자에게 원본 번호로 보여 주지 않는다. */
  public String displaySourceId() {
    return autoSourceId ? null : sourceId;
  }

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
        .map(
            warning ->
                warning.code()
                    + (warning.field() == null ? "" : "(" + warning.field() + ")")
                    + ": "
                    + warning.message())
        .reduce((left, right) -> left + " / " + right)
        .orElse("");
  }
}
