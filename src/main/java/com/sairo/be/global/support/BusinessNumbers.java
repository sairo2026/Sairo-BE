package com.sairo.be.global.support;

import java.util.Optional;
import java.util.regex.Pattern;

public final class BusinessNumbers {

  private static final Pattern DIGITS = Pattern.compile("^[0-9]{10}$");
  private static final int[] WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};

  private BusinessNumbers() {}

  public static Optional<String> normalize(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String digits = raw.strip().replace("-", "");
    return DIGITS.matcher(digits).matches() ? Optional.of(digits) : Optional.empty();
  }

  public static boolean hasValidChecksum(String digits) {
    if (digits == null || !DIGITS.matcher(digits).matches()) {
      return false;
    }
    int sum = 0;
    for (int i = 0; i < WEIGHTS.length; i++) {
      sum += (digits.charAt(i) - '0') * WEIGHTS[i];
    }
    sum += (digits.charAt(8) - '0') * 5 / 10;
    int check = (10 - sum % 10) % 10;
    return check == digits.charAt(9) - '0';
  }
}
