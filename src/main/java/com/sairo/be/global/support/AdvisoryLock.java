package com.sairo.be.global.support;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class AdvisoryLock {

  private final JdbcClient jdbcClient;

  public AdvisoryLock(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public void lockForTransaction(String key) {
    jdbcClient
        .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", key)
        .query()
        .listOfRows();
  }
}
