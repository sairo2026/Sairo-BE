package com.sairo.be.global.support;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

public final class InputRules {

  public static final int NAME_MAX = 100;
  public static final int SHORT_MEMO_MAX = 500;
  public static final int BODY_MEMO_MAX = 10_000;
  public static final int CUSTOM_FIELD_NAME_MAX = 50;
  public static final int WORK_MEMO_MAX = 2_000;

  private static final Pattern HTML_MARKUP = Pattern.compile("<\\s*[/!?]?\\s*[a-zA-Z]|<\\s*[/!?]");
  private static final int AREA_MAX_SCALE = 2;

  private InputRules() {}

  public static String normalize(String value) {
    return value == null ? null : value.strip();
  }

  public static int length(CharSequence value) {
    String stripped = value.toString().strip();
    return stripped.codePointCount(0, stripped.length());
  }

  public static boolean containsHtml(CharSequence value) {
    return HTML_MARKUP.matcher(value).find();
  }

  public static boolean isAcceptedText(CharSequence value, int min, int max) {
    if (value == null) {
      return false;
    }
    int length = length(value);
    return length >= min && length <= max && !containsHtml(value);
  }

  public static String requireText(String field, String value, int min, int max) {
    if (value == null) {
      throw invalid(field, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    String normalized = normalize(value);
    int length = length(normalized);
    if (length == 0 && min > 0) {
      throw invalid(field, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (containsHtml(normalized)) {
      throw invalid(field, FieldErrorCode.HTML_NOT_ALLOWED, "HTML은 입력할 수 없습니다.");
    }
    if (length < min) {
      throw invalid(field, FieldErrorCode.TOO_SHORT, min + "자 이상 입력해야 합니다.");
    }
    if (length > max) {
      throw invalid(field, FieldErrorCode.TOO_LONG, max + "자 이하로 입력해야 합니다.");
    }
    return normalized;
  }

  public static String requireName(String field, String value) {
    return requireText(field, value, 1, NAME_MAX);
  }

  public static String optionalText(String field, String value, int max) {
    if (value == null) {
      return null;
    }
    return requireText(field, value, 0, max);
  }

  public static long requireNonNegativeAmount(String field, long amount) {
    if (amount < 0) {
      throw invalid(field, FieldErrorCode.OUT_OF_RANGE, "0 이상이어야 합니다.");
    }
    return amount;
  }

  public static BigDecimal requireArea(String field, BigDecimal area) {
    if (area == null) {
      throw invalid(field, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (area.signum() <= 0) {
      throw invalid(field, FieldErrorCode.OUT_OF_RANGE, "0보다 커야 합니다.");
    }
    if (area.stripTrailingZeros().scale() > AREA_MAX_SCALE) {
      throw invalid(field, FieldErrorCode.INVALID_FORMAT, "소수 둘째 자리까지 입력할 수 있습니다.");
    }
    return area;
  }

  private static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
