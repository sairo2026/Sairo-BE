package com.sairo.be.global.support;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TokenGenerator {

  private static final int DEFAULT_TOKEN_BYTES = 32;
  private static final int MIN_TOKEN_BYTES = 16;
  private static final int MAX_CODE_DIGITS = 9;

  private final SecureRandom secureRandom = new SecureRandom();

  public String randomToken() {
    return randomToken(DEFAULT_TOKEN_BYTES);
  }

  public String randomToken(int byteLength) {
    if (byteLength < MIN_TOKEN_BYTES) {
      throw new IllegalArgumentException("토큰은 16바이트 이상이어야 합니다.");
    }
    byte[] bytes = new byte[byteLength];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String numericCode(int digits) {
    if (digits < 1 || digits > MAX_CODE_DIGITS) {
      throw new IllegalArgumentException("숫자 코드는 1자리에서 9자리까지 지원합니다.");
    }
    int bound = (int) Math.pow(10, digits);
    return String.format("%0" + digits + "d", secureRandom.nextInt(bound));
  }

  public String uuid() {
    return UUID.randomUUID().toString();
  }
}
