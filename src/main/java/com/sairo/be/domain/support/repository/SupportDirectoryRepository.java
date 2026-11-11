package com.sairo.be.domain.support.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class SupportDirectoryRepository {

  private final JdbcClient jdbcClient;

  public SupportDirectoryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Map<Long, String> operatorNames(Collection<Long> operatorIds) {
    Map<Long, String> names = new HashMap<>();
    if (operatorIds.isEmpty()) {
      return names;
    }
    jdbcClient
        .sql("SELECT id, name FROM operator_account WHERE id IN (:ids)")
        .param("ids", operatorIds)
        .query(
            (rs, row) -> {
              names.put(rs.getLong("id"), rs.getString("name"));
              return null;
            })
        .list();
    return names;
  }
}
