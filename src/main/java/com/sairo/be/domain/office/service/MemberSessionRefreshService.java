package com.sairo.be.domain.office.service;

import com.sairo.be.global.security.MemberSessionRefresher;
import com.sairo.be.global.security.SessionTerminator;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.AfterCommit;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Profile("!migrate")
@Service
public class MemberSessionRefreshService {

  private final JdbcClient jdbcClient;
  private final MemberSessionRefresher refresher;
  private final SessionTerminator terminator;

  public MemberSessionRefreshService(
      JdbcClient jdbcClient, MemberSessionRefresher refresher, SessionTerminator terminator) {
    this.jdbcClient = jdbcClient;
    this.refresher = refresher;
    this.terminator = terminator;
  }

  public void refreshAfterCommit(Long userId) {
    AfterCommit.run(() -> refresher.replacePrincipal(userId, principalOf(userId)));
  }

  public void terminateAfterCommit(List<Long> userIds) {
    AfterCommit.run(() -> userIds.forEach(terminator::terminateAll));
  }

  private StaffPrincipal principalOf(Long userId) {
    return jdbcClient
        .sql(
            "SELECT u.name, m.id AS membership_id, m.office_id, m.role FROM app_user u"
                + " LEFT JOIN office_membership m ON m.user_id = u.id AND m.status = 'APPROVED'"
                + " WHERE u.id = :id")
        .param("id", userId)
        .query(
            (rs, i) -> {
              String role = rs.getString("role");
              return new StaffPrincipal(
                  userId,
                  (Long) rs.getObject("membership_id"),
                  (Long) rs.getObject("office_id"),
                  rs.getString("name"),
                  role == null ? null : StaffRole.valueOf(role));
            })
        .optional()
        .orElseGet(() -> new StaffPrincipal(userId, null, null, "", null));
  }
}
