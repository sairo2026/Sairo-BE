package com.sairo.be.domain.ops.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class TotpService {

  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final int SECRET_BYTES = 20;
  private static final int STEP_SECONDS = 30;
  private static final int DIGITS = 6;
  private static final int WINDOW = 1;

  private final SecureRandom secureRandom = new SecureRandom();

  public String newSecret() {
    byte[] bytes = new byte[SECRET_BYTES];
    secureRandom.nextBytes(bytes);
    return encodeBase32(bytes);
  }

  public String otpauthUri(String secret) {
    return "otpauth://totp/SAIRO:ops?secret=" + secret + "&issuer=SAIRO";
  }

  public boolean verify(String secret, String code, Instant now) {
    if (code == null || !code.matches("\\d{6}")) {
      return false;
    }
    long counter = now.getEpochSecond() / STEP_SECONDS;
    boolean matched = false;
    for (int offset = -WINDOW; offset <= WINDOW; offset++) {
      String expected = codeAt(secret, counter + offset);
      matched |=
          MessageDigest.isEqual(
              expected.getBytes(StandardCharsets.US_ASCII),
              code.getBytes(StandardCharsets.US_ASCII));
    }
    return matched;
  }

  public String codeAt(String secret, long counter) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
      int offset = hash[hash.length - 1] & 0x0F;
      int binary =
          ((hash[offset] & 0x7F) << 24)
              | ((hash[offset + 1] & 0xFF) << 16)
              | ((hash[offset + 2] & 0xFF) << 8)
              | (hash[offset + 3] & 0xFF);
      int otp = binary % (int) Math.pow(10, DIGITS);
      return String.format("%0" + DIGITS + "d", otp);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("2차 인증 코드를 계산하지 못했습니다.", e);
    }
  }

  public static String encodeBase32(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte value : bytes) {
      buffer = (buffer << 8) | (value & 0xFF);
      bits += 8;
      while (bits >= 5) {
        out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
    }
    return out.toString();
  }

  public static byte[] decodeBase32(String encoded) {
    ByteBuffer out = ByteBuffer.allocate(encoded.length() * 5 / 8 + 1);
    int buffer = 0;
    int bits = 0;
    for (char c : encoded.toCharArray()) {
      int value = ALPHABET.indexOf(c);
      if (value < 0) {
        throw new IllegalArgumentException("base32 문자가 아닙니다.");
      }
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) {
        out.put((byte) ((buffer >> (bits - 8)) & 0xFF));
        bits -= 8;
      }
    }
    byte[] result = new byte[out.position()];
    out.flip();
    out.get(result);
    return result;
  }
}
