package com.sairo.be.domain.ops.service;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 운영 콘솔 목록 조회 파라미터 검사다. */
final class OpsQueryParams {

  private static final int KEYWORD_MAX = 100;
  private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,49}$");

  private OpsQueryParams() {}

  static String keyword(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip();
    if (value.length() > KEYWORD_MAX) {
      throw invalid("keyword", FieldErrorCode.OUT_OF_RANGE, "검색어는 100자 이하여야 합니다.");
    }
    return value;
  }

  static String oneOf(String field, String raw, Set<String> allowed) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip();
    if (!allowed.contains(value)) {
      throw invalid(field, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 " + field + " 값입니다.");
    }
    return value;
  }

  /** 행위 종류·대상 종류 같은 코드 값이다. 대문자·숫자·밑줄만 받는다. */
  static String code(String field, String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip();
    if (!CODE.matcher(value).matches()) {
      throw invalid(field, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 " + field + " 값입니다.");
    }
    return value;
  }

  static Long positiveId(String field, Long raw) {
    if (raw != null && raw < 1) {
      throw invalid(field, FieldErrorCode.OUT_OF_RANGE, field + "는 1 이상이어야 합니다.");
    }
    return raw;
  }

  /** ISO-8601 일시 또는 날짜(KST 0시)로 받는다. */
  static Instant instant(String field, String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.strip();
    try {
      if (value.length() == 10) {
        return TimeRules.startOfDisplayDay(LocalDate.parse(value));
      }
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      throw invalid(field, FieldErrorCode.INVALID_FORMAT, field + "는 ISO-8601 일시여야 합니다.");
    }
  }

  static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
