package com.sairo.be.domain.property.repository;

import com.sairo.be.domain.property.service.ManagingMemberDirectory;
import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class JdbcManagingMemberDirectory implements ManagingMemberDirectory {

  private final JdbcClient jdbcClient;

  public JdbcManagingMemberDirectory(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<ManagingMember> find(Long membershipId) {
    return jdbcClient
        .sql(
            "SELECT m.id, m.office_id, m.role, m.status, u.name,"
                + " (u.account_status = 'WITHDRAWN') AS withdrawn, m.withdrawn_alias_no"
                + " FROM office_membership m JOIN app_user u ON u.id = m.user_id"
                + " WHERE m.id = :id")
        .param("id", membershipId)
        .query(
            (rs, rowNum) ->
                new ManagingMember(
                    rs.getLong("id"),
                    rs.getLong("office_id"),
                    rs.getString("role"),
                    rs.getString("status"),
                    WithdrawnMemberDisplay.name(
                        rs.getBoolean("withdrawn"),
                        rs.getString("name"),
                        (Integer) rs.getObject("withdrawn_alias_no"))))
        .optional();
  }
}
