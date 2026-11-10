package com.sairo.be.global.support;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.security.SecurityProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Component
public class RequestRateLimiter {

  private static final String UPSERT =
      """
      INSERT INTO rate_limit_counter (bucket_key, window_started_at, request_count)
      VALUES (:key, :now, 1)
      ON CONFLICT (bucket_key) DO UPDATE SET
        request_count = CASE WHEN rate_limit_counter.window_started_at <= :windowStart
                             THEN 1 ELSE rate_limit_counter.request_count + 1 END,
        window_started_at = CASE WHEN rate_limit_counter.window_started_at <= :windowStart
                                 THEN :now ELSE rate_limit_counter.window_started_at END
      RETURNING request_count, window_started_at
      """;

  private final JdbcClient jdbcClient;
  private final SecurityProperties properties;
  private final Clock clock;

  public RequestRateLimiter(JdbcClient jdbcClient, SecurityProperties properties, Clock clock) {
    this.jdbcClient = jdbcClient;
    this.properties = properties;
    this.clock = clock;
  }

  public String bucketKey(RateLimitRule rule, String identifier) {
    return rule.name()
        + ":"
        + HmacSupport.hmacHex(properties.rateLimitHmacSecret(), rule.name() + "|" + identifier);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void hit(RateLimitRule rule, String identifier) {
    Result result = increment(rule, identifier);
    if (result.count() > rule.limit()) {
      throw BusinessException.rateLimited(result.retryAfterSeconds());
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Result increment(RateLimitRule rule, String identifier) {
    Instant now = clock.instant();
    Map<String, Object> row =
        jdbcClient
            .sql(UPSERT)
            .param("key", bucketKey(rule, identifier))
            .param("now", Timestamp.from(now))
            .param("windowStart", Timestamp.from(now.minus(rule.window())))
            .query()
            .singleRow();
    int count = ((Number) row.get("request_count")).intValue();
    Instant started = ((Timestamp) row.get("window_started_at")).toInstant();
    long retryAfter = Math.max(1L, Duration.between(now, started.plus(rule.window())).toSeconds());
    return new Result(count, retryAfter);
  }

  @Transactional(readOnly = true)
  public Result peek(RateLimitRule rule, String identifier) {
    Instant now = clock.instant();
    List<Map<String, Object>> rows =
        jdbcClient
            .sql(
                "SELECT request_count, window_started_at FROM rate_limit_counter"
                    + " WHERE bucket_key = :key")
            .param("key", bucketKey(rule, identifier))
            .query()
            .listOfRows();
    if (rows.isEmpty()) {
      return new Result(0, 0);
    }
    Instant started = ((Timestamp) rows.get(0).get("window_started_at")).toInstant();
    if (!started.plus(rule.window()).isAfter(now)) {
      return new Result(0, 0);
    }
    int count = ((Number) rows.get(0).get("request_count")).intValue();
    long retryAfter = Math.max(1L, Duration.between(now, started.plus(rule.window())).toSeconds());
    return new Result(count, retryAfter);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reset(RateLimitRule rule, String identifier) {
    jdbcClient
        .sql("DELETE FROM rate_limit_counter WHERE bucket_key = :key")
        .param("key", bucketKey(rule, identifier))
        .update();
  }

  public record Result(int count, long retryAfterSeconds) {}
}
