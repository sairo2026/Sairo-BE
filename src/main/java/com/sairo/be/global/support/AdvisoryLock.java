package com.sairo.be.global.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

  public void lockAllForTransaction(Collection<String> keys) {
    List<Long> hashes = new ArrayList<>();
    for (String key : keys) {
      Long hash =
          jdbcClient
              .sql("SELECT hashtextextended(:key, 0)")
              .param("key", key)
              .query(Long.class)
              .single();
      if (!hashes.contains(hash)) {
        hashes.add(hash);
      }
    }
    hashes.sort(Long::compare);
    for (Long hash : hashes) {
      jdbcClient
          .sql("SELECT pg_advisory_xact_lock(:hash)")
          .param("hash", hash)
          .query()
          .listOfRows();
    }
  }
}
