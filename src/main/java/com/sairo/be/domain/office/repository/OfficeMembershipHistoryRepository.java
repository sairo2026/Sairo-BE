package com.sairo.be.domain.office.repository;

import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class OfficeMembershipHistoryRepository {

  public enum Actor {
    APPLICANT,
    OWNER,
    SYSTEM
  }

  private final JdbcClient jdbcClient;

  public OfficeMembershipHistoryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public void insert(
      long membershipId,
      OfficeMembershipStatus from,
      OfficeMembershipStatus to,
      Actor actor,
      Long actorMembershipId,
      String reason,
      Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO office_membership_status_history (membership_id, from_status, to_status,"
                + " changed_by_type, changed_by_membership_id, reason, created_at)"
                + " VALUES (:id, :from, :to, :actor, :actorMembership, :reason, :now)")
        .param("id", membershipId)
        .param("from", from == null ? null : from.name())
        .param("to", to.name())
        .param("actor", actor.name())
        .param("actorMembership", actorMembershipId)
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .update();
  }
}
