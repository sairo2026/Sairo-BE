package com.sairo.be.domain.coordination.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PublicLinkTokenGenerator {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final SecretKeySpec key;

  public PublicLinkTokenGenerator(PublicLinkProperties properties) {
    this.key =
        new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
  }

  public String generate(Long linkId, Instant issuedAt) {
    String payload = linkId + ":" + issuedAt.toEpochMilli();
    byte[] signature = sign(payload);
    return encode(payload.getBytes(StandardCharsets.UTF_8)) + "." + encode(signature);
  }

  public String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
    }
  }

  private byte[] sign(String payload) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(key);
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("공개 링크 토큰 서명에 실패했습니다.", e);
    }
  }

  private String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
