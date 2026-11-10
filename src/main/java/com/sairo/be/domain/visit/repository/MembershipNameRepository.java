package com.sairo.be.domain.visit.repository;

import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class MembershipNameRepository {

  private final JdbcClient jdbcClient;

  public MembershipNameRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Map<Long, String> displayNames(Collection<Long> membershipIds) {
    Map<Long, String> names = new HashMap<>();
    if (membershipIds.isEmpty()) {
      return names;
    }
    jdbcClient
        .sql(
            "SELECT m.id, u.name, (u.account_status = 'WITHDRAWN') AS withdrawn,"
                + " m.withdrawn_alias_no FROM office_membership m JOIN app_user u ON u.id ="
                + " m.user_id WHERE m.id IN (:ids)")
        .param("ids", membershipIds)
        .query(
            (rs, rowNum) -> {
              int alias = rs.getInt("withdrawn_alias_no");
              Integer aliasNo = rs.wasNull() ? null : alias;
              names.put(
                  rs.getLong("id"),
                  WithdrawnMemberDisplay.name(
                      rs.getBoolean("withdrawn"), rs.getString("name"), aliasNo));
              return null;
            })
        .list();
    return names;
  }
}
