package com.sairo.be.domain.visit.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class ScheduleBusyRepository {

  public record BusyVisit(
      Long itemId,
      Long coordinationId,
      Long officeId,
      Long candidateId,
      Long propertyId,
      Instant startAt,
      Instant endAt,
      Instant travelStartsAt,
      Instant travelEndsAt) {}

  private static final String SELECT =
      "SELECT si.id, si.coordination_id, si.office_id, si.candidate_property_id,"
          + " cp.property_id, si.scheduled_start_at, si.scheduled_end_at, si.travel_starts_at,"
          + " si.travel_ends_at FROM visit_schedule_item si JOIN visit_candidate_property cp ON"
          + " cp.id = si.candidate_property_id JOIN visit_coordination vc ON vc.id ="
          + " si.coordination_id WHERE si.result = 'SCHEDULED' AND si.scheduled_start_at IS NOT"
          + " NULL AND si.scheduled_end_at IS NOT NULL";

  private final JdbcClient jdbcClient;

  public ScheduleBusyRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<BusyVisit> forManager(Long managerMembershipId) {
    return jdbcClient
        .sql(SELECT + " AND vc.manager_membership_id = :manager ORDER BY si.scheduled_start_at")
        .param("manager", managerMembershipId)
        .query((rs, rowNum) -> row(rs))
        .list();
  }

  public List<BusyVisit> forCustomers(Collection<Long> customerIds) {
    if (customerIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(SELECT + " AND vc.customer_id IN (:customers) ORDER BY si.scheduled_start_at")
        .param("customers", customerIds)
        .query((rs, rowNum) -> row(rs))
        .list();
  }

  public List<BusyVisit> forProperty(Long propertyId) {
    return jdbcClient
        .sql(SELECT + " AND cp.property_id = :property ORDER BY si.scheduled_start_at")
        .param("property", propertyId)
        .query((rs, rowNum) -> row(rs))
        .list();
  }

  public Long rootCustomer(Long customerId) {
    List<Long> chain =
        jdbcClient
            .sql(
                "WITH RECURSIVE up(id, depth) AS (SELECT CAST(:c AS BIGINT), 0 UNION ALL SELECT"
                    + " l.primary_customer_id, up.depth + 1 FROM customer_merge_log l JOIN up ON"
                    + " l.merged_customer_id = up.id WHERE up.depth < 50) SELECT id FROM up ORDER"
                    + " BY depth DESC LIMIT 1")
            .param("c", customerId)
            .query(Long.class)
            .list();
    return chain.isEmpty() ? customerId : chain.get(0);
  }

  public Set<Long> customerGroup(Long rootCustomerId) {
    List<Long> ids =
        jdbcClient
            .sql(
                "WITH RECURSIVE down(id, depth) AS (SELECT CAST(:c AS BIGINT), 0 UNION ALL SELECT"
                    + " l.merged_customer_id, down.depth + 1 FROM customer_merge_log l JOIN down"
                    + " ON l.primary_customer_id = down.id WHERE down.depth < 50) SELECT id FROM"
                    + " down")
            .param("c", rootCustomerId)
            .query(Long.class)
            .list();
    return new LinkedHashSet<>(new ArrayList<>(ids));
  }

  private static BusyVisit row(java.sql.ResultSet rs) throws java.sql.SQLException {
    java.sql.Timestamp travelStart = rs.getTimestamp("travel_starts_at");
    java.sql.Timestamp travelEnd = rs.getTimestamp("travel_ends_at");
    return new BusyVisit(
        rs.getLong("id"),
        rs.getLong("coordination_id"),
        rs.getLong("office_id"),
        rs.getLong("candidate_property_id"),
        rs.getLong("property_id"),
        rs.getTimestamp("scheduled_start_at").toInstant(),
        rs.getTimestamp("scheduled_end_at").toInstant(),
        travelStart == null ? null : travelStart.toInstant(),
        travelEnd == null ? null : travelEnd.toInstant());
  }
}
