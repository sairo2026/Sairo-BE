package com.sairo.be.global.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacSupport {

  private static final String HMAC_SHA256 = "HmacSHA256";

  private HmacSupport() {}

  public static byte[] hmac(String secret, String value) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC 계산에 실패했습니다.", e);
    }
  }

  public static String hmacHex(String secret, String value) {
    return HexFormat.of().formatHex(hmac(secret, value));
  }

  public static String hmacBase64Url(String secret, String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(secret, value));
  }

  public static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 계산에 실패했습니다.", e);
    }
  }

  public static boolean constantTimeEquals(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }
}
