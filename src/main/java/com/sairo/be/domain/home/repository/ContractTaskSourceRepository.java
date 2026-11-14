package com.sairo.be.domain.home.repository;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 계약 업무의 원천(contract와 매물명)을 읽기만 한다. 계약 도메인 코드에 기대지 않으려고 JDBC로 읽는다. */
@Profile("!migrate")
@Repository
public class ContractTaskSourceRepository {

  public record ContractSource(
      Long contractId,
      Long officeId,
      Long propertyId,
      String propertyName,
      Long managerMembershipId,
      String dealType,
      String contractStatus,
      boolean excluded,
      LocalDate balanceDueDate,
      LocalDate leaseEndDate) {}

  private final JdbcClient jdbcClient;

  public ContractTaskSourceRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<ContractSource> find(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT c.id, c.office_id, c.property_id, p.name, c.managing_membership_id,"
                + " c.deal_type, c.contract_status, c.duplicate_excluded_at IS NOT NULL AS"
                + " excluded, c.balance_due_date, c.lease_end_date FROM contract c JOIN property"
                + " p ON p.id = c.property_id WHERE c.id = :id")
        .param("id", contractId)
        .query(
            (rs, rowNum) ->
                new ContractSource(
                    rs.getLong("id"),
                    rs.getLong("office_id"),
                    rs.getLong("property_id"),
                    rs.getString("name"),
                    rs.getLong("managing_membership_id"),
                    rs.getString("deal_type"),
                    rs.getString("contract_status"),
                    rs.getBoolean("excluded"),
                    rs.getObject("balance_due_date", LocalDate.class),
                    rs.getObject("lease_end_date", LocalDate.class)))
        .optional();
  }
}
