package com.sairo.be.global.support;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.util.List;

public final class IfMatchVersion {

  private IfMatchVersion() {}

  public static long parse(String header) {
    if (header == null || header.isBlank()) {
      throw invalid(FieldErrorCode.REQUIRED, "If-Match 헤더에 현재 version을 보내야 합니다.");
    }
    String value = header.strip();
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed < 1) {
        throw invalid(FieldErrorCode.OUT_OF_RANGE, "If-Match 값은 1 이상의 십진수여야 합니다.");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw invalid(FieldErrorCode.INVALID_FORMAT, "If-Match 값은 십진수여야 합니다.");
    }
  }

  private static BusinessException invalid(FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem("If-Match", code, message)));
  }
}
