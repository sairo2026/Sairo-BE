package com.sairo.be.domain.visit.repository;

import com.sairo.be.global.port.AssistantAssignments;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class JdbcAssistantAssignments implements AssistantAssignments {

  private final JdbcClient jdbcClient;

  public JdbcAssistantAssignments(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public boolean isAssignedCustomer(Long officeId, Long membershipId, Long customerId) {
    return jdbcClient
        .sql(
            "SELECT EXISTS (SELECT 1 FROM visit_coordination WHERE office_id = :office"
                + " AND manager_membership_id = :member AND customer_id = :customer)")
        .param("office", officeId)
        .param("member", membershipId)
        .param("customer", customerId)
        .query(Boolean.class)
        .single();
  }
}
