package com.sairo.be.domain.customer.repository;

import com.sairo.be.global.port.MemberWorkHandover;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 보관되지 않은 고객의 담당자를 넘긴다. 상담 기록의 작성자는 그대로 둔다. */
@Profile("!migrate")
@Repository
public class JdbcCustomerWorkHandover implements MemberWorkHandover {

  private final JdbcClient jdbcClient;

  public JdbcCustomerWorkHandover(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public WorkKind kind() {
    return WorkKind.CUSTOMER;
  }

  @Override
  public long countOpen(Long officeId, Long membershipId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM customer WHERE office_id = :office AND managing_membership_id = :member"
                + " AND deleted_at IS NULL")
        .param("office", officeId)
        .param("member", membershipId)
        .query(Long.class)
        .single();
  }

  @Override
  public int handOver(Long officeId, Long fromMembershipId, Long toMembershipId, Instant now) {
    return jdbcClient
        .sql(
            "UPDATE customer SET managing_membership_id = :to, updated_at = :now, version = version + 1"
                + " WHERE office_id = :office AND managing_membership_id = :from"
                + " AND deleted_at IS NULL")
        .param("to", toMembershipId)
        .param("now", Timestamp.from(now))
        .param("office", officeId)
        .param("from", fromMembershipId)
        .update();
  }
}
