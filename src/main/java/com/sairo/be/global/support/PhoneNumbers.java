package com.sairo.be.global.support;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PhoneNumbers {

  private static final Pattern MOBILE = Pattern.compile("^(01[016789])-?(\\d{3,4})-?(\\d{4})$");

  private PhoneNumbers() {}

  public static Optional<String> normalize(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    Matcher matcher = MOBILE.matcher(raw.strip());
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3));
  }
}
