package com.sairo.be.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

@IntegrationTest
class OAuthStateServiceTest {

  @Autowired private OAuthStateService service;
  @Autowired private StringRedisTemplate redisTemplate;

  @Test
  void 발급한_state는_같은_세션에서_한_번만_소비할_수_있다() {
    String state = service.issue("session-a");

    assertThat(service.consume("session-a", state)).isTrue();
    assertThat(service.consume("session-a", state)).isFalse();
  }

  @Test
  void 다른_세션에서는_state를_소비할_수_없고_시도한_state는_폐기된다() {
    String state = service.issue("session-a");

    assertThat(service.consume("session-b", state)).isFalse();
    assertThat(service.consume("session-a", state)).isFalse();
  }

  @Test
  void 발급하지_않은_state와_빈_값은_거절한다() {
    assertThat(service.consume("session-a", "never-issued")).isFalse();
    assertThat(service.consume("session-a", null)).isFalse();
    assertThat(service.consume("session-a", " ")).isFalse();
    assertThat(service.consume(null, "x")).isFalse();
  }

  @Test
  void state는_원문이_아닌_해시_키로_10분_만료를_걸어_저장한다() throws Exception {
    String state = service.issue("session-ttl");

    String key = "sairo:oauth-state:" + sha256(state);
    assertThat(redisTemplate.hasKey(key)).isTrue();
    assertThat(redisTemplate.keys("*" + state + "*")).isEmpty();
    Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
    assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(10).toSeconds());
  }

  @Test
  void 동시에_같은_state로_소비해도_한_요청만_성공한다() throws Exception {
    String state = service.issue("session-race");

    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Callable<Boolean>> tasks =
          java.util.stream.IntStream.range(0, 8)
              .<Callable<Boolean>>mapToObj(i -> () -> service.consume("session-race", state))
              .toList();
      long successes =
          executor.invokeAll(tasks).stream()
              .filter(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .count();
      assertThat(successes).isEqualTo(1);
    }
  }

  private String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
