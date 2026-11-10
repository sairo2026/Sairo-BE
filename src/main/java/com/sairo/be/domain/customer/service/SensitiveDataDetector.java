package com.sairo.be.domain.customer.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SensitiveDataDetector {

  private static final Pattern RESIDENT_NUMBER =
      Pattern.compile("(?<!\\d)\\d{6}[- ]?[1-8]\\d{6}(?!\\d)");
  private static final Pattern DIGIT_RUN = Pattern.compile("(?<!\\d)\\d(?:[\\d-]{7,22})\\d(?!\\d)");
  private static final Pattern PHONE_LIKE = Pattern.compile("^0\\d{1,2}-?\\d{3,4}-?\\d{4}$");
  private static final int ACCOUNT_MIN_DIGITS = 10;
  private static final int ACCOUNT_MAX_DIGITS = 16;

  private SensitiveDataDetector() {}

  public static boolean detect(String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }
    if (RESIDENT_NUMBER.matcher(text).find()) {
      return true;
    }
    Matcher matcher = DIGIT_RUN.matcher(text);
    while (matcher.find()) {
      String token = matcher.group();
      if (PHONE_LIKE.matcher(token).matches()) {
        continue;
      }
      int digits = token.replace("-", "").length();
      if (digits >= ACCOUNT_MIN_DIGITS && digits <= ACCOUNT_MAX_DIGITS) {
        return true;
      }
    }
    return false;
  }
}
