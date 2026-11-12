package com.sairo.be.domain.home.repository;

import com.sairo.be.global.port.MemberWorkHandover;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 끝나지 않은 업무의 담당자를 넘긴다. 완료·취소 업무는 과거 기록이라 그대로 둔다. */
@Profile("!migrate")
@Repository
public class JdbcTaskWorkHandover implements MemberWorkHandover {

  private final JdbcClient jdbcClient;

  public JdbcTaskWorkHandover(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public WorkKind kind() {
    return WorkKind.TASK;
  }

  @Override
  public long countOpen(Long officeId, Long membershipId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM task WHERE office_id = :office AND assignee_membership_id = :member"
                + " AND status = 'TODO'")
        .param("office", officeId)
        .param("member", membershipId)
        .query(Long.class)
        .single();
  }

  @Override
  public int handOver(Long officeId, Long fromMembershipId, Long toMembershipId, Instant now) {
    return jdbcClient
        .sql(
            "UPDATE task SET assignee_membership_id = :to, updated_at = :now, version = version + 1"
                + " WHERE office_id = :office AND assignee_membership_id = :from"
                + " AND status = 'TODO'")
        .param("to", toMembershipId)
        .param("now", Timestamp.from(now))
        .param("office", officeId)
        .param("from", fromMembershipId)
        .update();
  }
}
