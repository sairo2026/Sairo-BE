package com.sairo.be.global.support;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 승인자 연락처 저장 값의 암호화와 복호화다. 암호문은 판별 접두사로 시작한다. 접두사가 없는 값은 암호화 전에 저장된 값이므로 그대로 읽고, 암호화 배치가 암호문으로 바꾼다.
 */
@Profile("!migrate")
@Component
public class ApproverPhoneCipher {

  public static final String PREFIX = "enc:v1:";

  private final TextCipher textCipher;

  public ApproverPhoneCipher(TextCipher textCipher) {
    this.textCipher = textCipher;
  }

  public String encrypt(String phone) {
    return phone == null ? null : PREFIX + textCipher.encrypt(phone);
  }

  public String decrypt(String stored) {
    if (stored == null || !isEncrypted(stored)) {
      return stored;
    }
    return textCipher.decrypt(stored.substring(PREFIX.length()));
  }

  public static boolean isEncrypted(String stored) {
    return stored != null && stored.startsWith(PREFIX);
  }
}
