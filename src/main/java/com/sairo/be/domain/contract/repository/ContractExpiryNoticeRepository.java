package com.sairo.be.domain.contract.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 임대차 만기 알림의 대상 계약과 수신자를 읽는다. */
@Profile("!migrate")
@Repository
public class ContractExpiryNoticeRepository {

  public record Target(
      Long contractId,
      Long officeId,
      String propertyName,
      Long managingMembershipId,
      LocalDate leaseEndDate) {}

  private final JdbcClient jdbcClient;

  public ContractExpiryNoticeRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** 체결된 전세·월세 가운데 중복 제외되지 않았고 만기일이 오늘부터 기준일 사이인 계약이다. 이미 만기가 지난 계약은 빠진다. */
  public List<Target> targets(LocalDate today, LocalDate limit) {
    return jdbcClient
        .sql(
            "SELECT c.id, c.office_id, p.name, c.managing_membership_id, c.lease_end_date FROM"
                + " contract c JOIN property p ON p.id = c.property_id WHERE c.contract_status ="
                + " 'SIGNED' AND c.deal_type IN ('JEONSE','MONTHLY') AND c.duplicate_excluded_at"
                + " IS NULL AND c.lease_end_date BETWEEN :today AND :limit ORDER BY c.id")
        .param("today", today)
        .param("limit", limit)
        .query(
            (rs, rowNum) ->
                new Target(
                    rs.getLong("id"),
                    rs.getLong("office_id"),
                    rs.getString("name"),
                    rs.getLong("managing_membership_id"),
                    rs.getObject("lease_end_date", LocalDate.class)))
        .list();
  }

  /** 담당 중개사와 사무소의 승인된 대표 사용자를 겹치지 않게 돌려준다. */
  public List<Long> recipientUserIds(Long officeId, Long managingMembershipId) {
    return jdbcClient
        .sql(
            "SELECT DISTINCT user_id FROM office_membership WHERE office_id = :office AND status"
                + " = 'APPROVED' AND (id = :manager OR role = 'OWNER') ORDER BY user_id")
        .param("office", officeId)
        .param("manager", managingMembershipId)
        .query(Long.class)
        .list();
  }
}
