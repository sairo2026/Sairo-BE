package com.sairo.be.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseCleaner {

  private static final String TRUNCATE =
      "TRUNCATE TABLE app_user, office, operator_account, terms_catalog, rate_limit_counter,"
          + " legal_kakao_unlink_event, legal_retention_rule, deletion_queue,"
          + " price_schedule, trial_event, company_service_info, approver_profile,"
          + " billing_reconciliation_entry, system_job_run"
          + " RESTART IDENTITY CASCADE";

  private final JdbcTemplate jdbcTemplate;
  private final StringRedisTemplate redisTemplate;

  public DatabaseCleaner(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.redisTemplate = redisTemplate;
  }

  public void clean() {
    jdbcTemplate.execute(TRUNCATE);
    seedPropertyDefaults();
    var factory = redisTemplate.getConnectionFactory();
    if (factory != null) {
      try (var connection = factory.getConnection()) {
        connection.serverCommands().flushAll();
      }
    }
  }

  private void seedPropertyDefaults() {
    try {
      Resource[] seeds =
          new PathMatchingResourcePatternResolver()
              .getResources("classpath:db/migration/V*__seed_property_default_categories.sql");
      for (Resource seed : seeds) {
        jdbcTemplate.execute(seed.getContentAsString(StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new IllegalStateException("기본 카테고리 시드를 읽을 수 없습니다.", e);
    }
  }
}
