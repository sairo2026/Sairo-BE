package com.sairo.be.domain.migration.repository;

import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class ImportSupportJdbcRepository {

  private final JdbcClient jdbcClient;

  public ImportSupportJdbcRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<String> findOperatorName(long operatorId) {
    return jdbcClient
        .sql("SELECT name FROM operator_account WHERE id = :id")
        .param("id", operatorId)
        .query(String.class)
        .optional();
  }

  public Optional<StaffPrincipal> findOwnerPrincipal(long membershipId, long officeId) {
    return jdbcClient
        .sql(
            "SELECT m.user_id, u.name FROM office_membership m JOIN app_user u ON u.id = m.user_id"
                + " WHERE m.id = :membershipId AND m.office_id = :officeId")
        .param("membershipId", membershipId)
        .param("officeId", officeId)
        .query(
            (rs, rowNum) ->
                new StaffPrincipal(
                    rs.getLong(1), membershipId, officeId, rs.getString(2), StaffRole.OWNER))
        .optional();
  }
}
