package com.sairo.be.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseCleaner {

  private static final String TRUNCATE =
      "TRUNCATE TABLE app_user, office, terms_catalog, rate_limit_counter,"
          + " legal_kakao_unlink_event, legal_retention_rule, deletion_queue,"
          + " price_schedule, trial_event, operator_account, company_service_info"
          + " RESTART IDENTITY CASCADE";

  private final JdbcTemplate jdbcTemplate;
  private final StringRedisTemplate redisTemplate;

  public DatabaseCleaner(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.redisTemplate = redisTemplate;
  }

  public void clean() {
    jdbcTemplate.execute(TRUNCATE);
    var factory = redisTemplate.getConnectionFactory();
    if (factory != null) {
      try (var connection = factory.getConnection()) {
        connection.serverCommands().flushAll();
      }
    }
  }
}
