package com.sairo.be.domain.customer.service;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.support.InputRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class CustomerInput {

  private CustomerInput() {}

  public static void requireObject(JsonNode body, Set<String> allowed) {
    if (body == null || !body.isObject()) {
      throw invalid("body", FieldErrorCode.TYPE_MISMATCH, "JSON 객체여야 합니다.");
    }
    for (String name : body.propertyNames()) {
      if (!allowed.contains(name)) {
        throw invalid(name, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
      }
    }
  }

  public static boolean has(JsonNode body, String key) {
    return body.has(key);
  }

  public static boolean isNull(JsonNode body, String key) {
    return !body.has(key) || body.get(key).isNull();
  }

  public static String requiredText(JsonNode body, String key, int max, boolean sensitiveCheck) {
    if (isNull(body, key)) {
      throw invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    return text(body, key, 1, max, sensitiveCheck);
  }

  public static String optionalText(JsonNode body, String key, int max, boolean sensitiveCheck) {
    if (isNull(body, key)) {
      return null;
    }
    return text(body, key, 1, max, sensitiveCheck);
  }

  public static String rejectIdentityNumber(String key, String value) {
    if (SensitiveDataDetector.detectIdentityNumber(value)) {
      throw BusinessException.withFieldErrors(
          ErrorCode.SENSITIVE_DATA_DETECTED,
          List.of(
              new FieldErrorItem(
                  key, FieldErrorCode.INVALID_VALUE, "주민등록번호·외국인등록번호 형식은 적을 수 없습니다.")));
    }
    return value;
  }

  private static String text(JsonNode body, String key, int min, int max, boolean sensitiveCheck) {
    JsonNode node = body.get(key);
    if (!node.isString()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    String value = InputRules.requireText(key, node.asString(), min, max);
    if (sensitiveCheck && SensitiveDataDetector.detect(value)) {
      throw BusinessException.withFieldErrors(
          ErrorCode.SENSITIVE_DATA_DETECTED,
          List.of(new FieldErrorItem(key, FieldErrorCode.INVALID_VALUE, "민감 번호가 감지됐습니다.")));
    }
    return value;
  }

  public static Boolean requiredBoolean(JsonNode body, String key) {
    if (isNull(body, key)) {
      throw invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    JsonNode node = body.get(key);
    if (!node.isBoolean()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "true 또는 false여야 합니다.");
    }
    return node.booleanValue();
  }

  public static LocalDate requiredDate(JsonNode body, String key) {
    if (isNull(body, key)) {
      throw invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    return date(body, key);
  }

  public static LocalDate optionalDate(JsonNode body, String key) {
    return isNull(body, key) ? null : date(body, key);
  }

  private static LocalDate date(JsonNode body, String key) {
    JsonNode node = body.get(key);
    if (!node.isString()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "날짜 문자열이어야 합니다.");
    }
    try {
      return LocalDate.parse(node.asString());
    } catch (DateTimeParseException e) {
      throw invalid(key, FieldErrorCode.INVALID_FORMAT, "yyyy-MM-dd 형식이어야 합니다.");
    }
  }

  public static OffsetDateTime requiredDateTime(JsonNode body, String key) {
    if (isNull(body, key)) {
      throw invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    return dateTime(body, key);
  }

  public static OffsetDateTime optionalDateTime(JsonNode body, String key) {
    return isNull(body, key) ? null : dateTime(body, key);
  }

  private static OffsetDateTime dateTime(JsonNode body, String key) {
    JsonNode node = body.get(key);
    if (!node.isString()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "일시 문자열이어야 합니다.");
    }
    try {
      return OffsetDateTime.parse(node.asString());
    } catch (DateTimeParseException e) {
      throw invalid(key, FieldErrorCode.INVALID_FORMAT, "ISO-8601 일시 형식이어야 합니다.");
    }
  }

  public static Long requiredLong(JsonNode body, String key) {
    if (isNull(body, key)) {
      throw invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    return longValue(body, key);
  }

  public static Long optionalLong(JsonNode body, String key) {
    return isNull(body, key) ? null : longValue(body, key);
  }

  private static Long longValue(JsonNode body, String key) {
    JsonNode node = body.get(key);
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
    }
    return node.longValue();
  }

  public static Long optionalAmount(JsonNode body, String key) {
    Long value = optionalLong(body, key);
    if (value != null && value < 0) {
      throw invalid(key, FieldErrorCode.OUT_OF_RANGE, "0 이상이어야 합니다.");
    }
    return value;
  }

  public static BigDecimal optionalArea(JsonNode body, String key) {
    if (isNull(body, key)) {
      return null;
    }
    JsonNode node = body.get(key);
    if (!node.isNumber()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "숫자여야 합니다.");
    }
    BigDecimal value = node.decimalValue();
    if (value.signum() < 0) {
      throw invalid(key, FieldErrorCode.OUT_OF_RANGE, "0 이상이어야 합니다.");
    }
    return value;
  }

  public static <E extends Enum<E>> E requiredEnum(JsonNode body, String key, Class<E> type) {
    if (isNull(body, key)) {
      throw invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    return enumValue(body, key, type);
  }

  public static <E extends Enum<E>> E enumValue(JsonNode body, String key, Class<E> type) {
    JsonNode node = body.get(key);
    if (!node.isString()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    try {
      return Enum.valueOf(type, node.asString());
    } catch (IllegalArgumentException e) {
      throw invalid(key, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
  }

  public static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
