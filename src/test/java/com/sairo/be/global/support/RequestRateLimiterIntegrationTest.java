package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.DatabaseCleaner;
import com.sairo.be.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class RequestRateLimiterIntegrationTest {

  private static final RateLimitRule THREE_PER_MINUTE =
      RateLimitRule.of("test-three", 3, Duration.ofMinutes(1));

  @Autowired private RequestRateLimiter rateLimiter;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StringRedisTemplate redisTemplate;

  @BeforeEach
  void clean() {
    new DatabaseCleaner(jdbcTemplate, redisTemplate).clean();
  }

  @Test
  void 한도_안의_요청은_통과하고_초과하면_429와_대기_시간을_돌려준다() {
    for (int i = 0; i < 3; i++) {
      rateLimiter.hit(THREE_PER_MINUTE, "subject");
    }

    assertThatThrownBy(() -> rateLimiter.hit(THREE_PER_MINUTE, "subject"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat((Long) e.getDetails().get("retryAfterSeconds")).isBetween(1L, 60L);
            });
  }

  @Test
  void 식별값이_다르면_서로_영향을_주지_않는다() {
    for (int i = 0; i < 3; i++) {
      rateLimiter.hit(THREE_PER_MINUTE, "a");
    }

    rateLimiter.hit(THREE_PER_MINUTE, "b");
  }

  @Test
  void 규칙_이름이_다르면_같은_식별값도_따로_센다() {
    RateLimitRule other = RateLimitRule.of("test-other", 1, Duration.ofMinutes(1));
    rateLimiter.hit(other, "a");

    for (int i = 0; i < 3; i++) {
      rateLimiter.hit(THREE_PER_MINUTE, "a");
    }
  }

  @Test
  void 윈도우가_지나면_카운터가_새로_시작한다() throws Exception {
    RateLimitRule shortWindow = RateLimitRule.of("test-short", 1, Duration.ofSeconds(1));
    rateLimiter.hit(shortWindow, "a");
    assertThatThrownBy(() -> rateLimiter.hit(shortWindow, "a"))
        .isInstanceOf(BusinessException.class);

    Thread.sleep(1_200);

    rateLimiter.hit(shortWindow, "a");
  }

  @Test
  void 초기화하면_다시_허용한다() {
    for (int i = 0; i < 3; i++) {
      rateLimiter.hit(THREE_PER_MINUTE, "a");
    }

    rateLimiter.reset(THREE_PER_MINUTE, "a");

    rateLimiter.hit(THREE_PER_MINUTE, "a");
    assertThat(rateLimiter.peek(THREE_PER_MINUTE, "a").count()).isEqualTo(1);
  }

  @Test
  void 조회는_카운터를_올리지_않고_윈도우가_끝난_값은_0이다() throws Exception {
    assertThat(rateLimiter.peek(THREE_PER_MINUTE, "a").count()).isZero();
    rateLimiter.hit(THREE_PER_MINUTE, "a");
    rateLimiter.peek(THREE_PER_MINUTE, "a");
    assertThat(rateLimiter.peek(THREE_PER_MINUTE, "a").count()).isEqualTo(1);

    RateLimitRule shortWindow = RateLimitRule.of("test-short-peek", 5, Duration.ofSeconds(1));
    rateLimiter.hit(shortWindow, "a");
    Thread.sleep(1_200);
    assertThat(rateLimiter.peek(shortWindow, "a").count()).isZero();
  }

  @Test
  void 식별값_원문은_저장하지_않고_HMAC_변환값만_저장한다() {
    rateLimiter.hit(THREE_PER_MINUTE, "agent@example.com");

    List<String> keys =
        jdbcTemplate.queryForList("SELECT bucket_key FROM rate_limit_counter", String.class);
    assertThat(keys).hasSize(1);
    assertThat(keys.get(0))
        .startsWith("test-three:")
        .doesNotContain("agent")
        .doesNotContain("example");
    assertThat(keys.get(0).length()).isEqualTo("test-three:".length() + 64);
  }

  @Test
  void 동시_요청도_한도를_넘기지_않는다() throws Exception {
    RateLimitRule rule = RateLimitRule.of("test-concurrent", 5, Duration.ofMinutes(1));
    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      List<Future<Boolean>> results =
          executor.invokeAll(
              java.util.stream.IntStream.range(0, 20)
                  .<Callable<Boolean>>mapToObj(
                      i ->
                          () -> {
                            try {
                              rateLimiter.hit(rule, "same");
                              return true;
                            } catch (BusinessException e) {
                              return false;
                            }
                          })
                  .toList());
      long passed = 0;
      for (Future<Boolean> result : results) {
        if (result.get()) {
          passed++;
        }
      }
      assertThat(passed).isEqualTo(5);
    } finally {
      executor.shutdownNow();
    }
  }
}
