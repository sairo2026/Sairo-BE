package com.sairo.be.global.support;

import java.util.regex.Pattern;

public final class LogMasking {

  private static final String MASK = "***";
  private static final int TOKEN_VISIBLE = 4;
  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
  private static final Pattern PHONE_PATTERN =
      Pattern.compile("(?<![0-9])01[016789][- ]?[0-9]{3,4}[- ]?([0-9]{4})(?![0-9])");
  private static final Pattern SECRET_PARAMETER_PATTERN =
      Pattern.compile("(?i)(token|ticket|code|password|newPassword|secret)=([^&\\s\"]+)");

  private static final Pattern LINK_PATH_TOKEN_PATTERN =
      Pattern.compile(
          "(?i)(/api/public/(?:[a-z-]+-visit-links|approver-property-links)/)[^/\\s?\"]+");

  private LogMasking() {}

  public static String email(String email) {
    if (email == null || email.isBlank()) {
      return MASK;
    }
    int at = email.indexOf('@');
    if (at <= 0) {
      return MASK;
    }
    return email.charAt(0) + MASK + email.substring(at);
  }

  public static String phone(String phone) {
    if (phone == null || phone.isBlank()) {
      return MASK;
    }
    String digits = phone.replaceAll("\\D", "");
    if (digits.length() < 4) {
      return MASK;
    }
    return MASK + digits.substring(digits.length() - 4);
  }

  public static String token(String token) {
    if (token == null || token.length() <= TOKEN_VISIBLE * 2) {
      return MASK;
    }
    return token.substring(0, TOKEN_VISIBLE) + MASK;
  }

  public static String maskSensitive(String text) {
    if (text == null) {
      return null;
    }
    String masked = EMAIL_PATTERN.matcher(text).replaceAll("$1" + MASK + "$2");
    masked = PHONE_PATTERN.matcher(masked).replaceAll(MASK + "$1");
    masked = LINK_PATH_TOKEN_PATTERN.matcher(masked).replaceAll("$1" + MASK);
    return SECRET_PARAMETER_PATTERN.matcher(masked).replaceAll("$1=" + MASK);
  }
}
