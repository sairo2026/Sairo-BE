package com.sairo.be.global.support;

import com.sairo.be.global.security.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class TextCipher {

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  public TextCipher(SecurityProperties properties) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(properties.kakaoKeyEncryptionSecret().getBytes(StandardCharsets.UTF_8));
      this.key = new SecretKeySpec(digest, "AES");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("암호화 키를 만들지 못했습니다.", e);
    }
  }

  public String encrypt(String plain) {
    try {
      byte[] iv = new byte[IV_BYTES];
      secureRandom.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] joined = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, joined, 0, iv.length);
      System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(joined);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("암호화에 실패했습니다.", e);
    }
  }

  public String decrypt(String encoded) {
    try {
      byte[] joined = Base64.getDecoder().decode(encoded);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, joined, 0, IV_BYTES));
      byte[] plain = cipher.doFinal(joined, IV_BYTES, joined.length - IV_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("복호화에 실패했습니다.", e);
    }
  }
}
