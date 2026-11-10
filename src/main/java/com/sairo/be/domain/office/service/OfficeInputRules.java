package com.sairo.be.domain.office.service;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.util.List;
import java.util.regex.Pattern;

final class OfficeInputRules {

  private static final Pattern BUSINESS_NUMBER = Pattern.compile("^\\d{10}$");
  private static final Pattern PHONE = Pattern.compile("^[0-9+()\\- ]{7,30}$");

  private OfficeInputRules() {}

  static String businessNumber(String field, String raw) {
    String digits = raw == null ? "" : raw.strip().replace("-", "");
    if (!BUSINESS_NUMBER.matcher(digits).matches()) {
      throw invalid(field, FieldErrorCode.INVALID_FORMAT, "사업자등록번호는 숫자 10자리여야 합니다.");
    }
    return digits;
  }

  static String phone(String field, String raw) {
    String value = raw == null ? "" : raw.strip();
    if (!PHONE.matcher(value).matches()) {
      throw invalid(field, FieldErrorCode.INVALID_FORMAT, "연락처 형식이 올바르지 않습니다.");
    }
    return value;
  }

  static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
