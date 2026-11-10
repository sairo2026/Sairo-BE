package com.sairo.be.domain.visit.repository;

import com.sairo.be.global.port.OfficeDirectory;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class JdbcOfficeDirectory implements OfficeDirectory {

  private final JdbcClient jdbcClient;

  public JdbcOfficeDirectory(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<OfficeSummary> find(Long officeId) {
    return jdbcClient
        .sql("SELECT id, name, representative_phone FROM office WHERE id = :id")
        .param("id", officeId)
        .query(
            (rs, rowNum) ->
                new OfficeSummary(
                    rs.getLong("id"), rs.getString("name"), rs.getString("representative_phone")))
        .optional();
  }
}
