package com.sairo.be.global.support;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PhoneNumbers {

  private static final Pattern MOBILE = Pattern.compile("^(01[016789])-?(\\d{3,4})-?(\\d{4})$");

  private static final Pattern CONTACT_CHARACTERS = Pattern.compile("^\\+?[0-9()\\- ]+$");
  private static final int CONTACT_MAX_LENGTH = 30;
  private static final int CONTACT_MIN_DIGITS = 7;
  private static final int CONTACT_MAX_DIGITS = 15;

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

  public static boolean isContactNumber(String raw) {
    if (raw == null) {
      return false;
    }
    String value = raw.strip();
    if (value.length() > CONTACT_MAX_LENGTH || !CONTACT_CHARACTERS.matcher(value).matches()) {
      return false;
    }
    long digits = value.chars().filter(c -> c >= '0' && c <= '9').count();
    return digits >= CONTACT_MIN_DIGITS && digits <= CONTACT_MAX_DIGITS;
  }
}
