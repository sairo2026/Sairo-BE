package com.sairo.be.global.port.toss;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TossWebhookVerifier {

  private static final String SIGNATURE_PREFIX = "v1:";

  private TossWebhookVerifier() {}

  public static boolean verify(
      String rawPayload, String transmissionTime, String signatureHeader, String secret) {
    if (rawPayload == null
        || transmissionTime == null
        || signatureHeader == null
        || secret == null
        || secret.isBlank()) {
      return false;
    }
    byte[] expected = sign(rawPayload, transmissionTime, secret);
    boolean matched = false;
    for (String candidate : signatureHeader.split(",")) {
      String value = candidate.trim();
      if (value.startsWith(SIGNATURE_PREFIX)) {
        value = value.substring(SIGNATURE_PREFIX.length());
      }
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(value);
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (MessageDigest.isEqual(expected, decoded)) {
        matched = true;
      }
    }
    return matched;
  }

  static byte[] sign(String rawPayload, String transmissionTime, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal((rawPayload + ":" + transmissionTime).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
