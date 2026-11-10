package com.sairo.be.domain.auth.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
@RequiredArgsConstructor
public class OAuthStateRepository {

  private static final String KEY_PREFIX = "sairo:oauth-state:";

  private final StringRedisTemplate redisTemplate;

  public void save(String state, String sessionId, Duration timeToLive) {
    redisTemplate.opsForValue().set(key(state), sessionId, timeToLive);
  }

  public Optional<String> consume(String state) {
    return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(key(state)));
  }

  private String key(String state) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
      return KEY_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
    }
  }
}
