package com.sairo.be.domain.home.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 방문 업무의 원천(visit_schedule_item과 조율 건)을 읽기만 한다. 방문 도메인 코드에 기대지 않으려고 JDBC로 읽는다. */
@Profile("!migrate")
@Repository
public class VisitTaskSourceRepository {

  public record ItemSource(
      Long itemId,
      Long officeId,
      Long coordinationId,
      Long managerMembershipId,
      Long customerId,
      Long propertyId,
      Long propertyOfficeId,
      String propertyName,
      Instant startAt,
      String result) {}

  public record CoordinationHead(Long officeId, Long managerMembershipId) {}

  private final JdbcClient jdbcClient;

  public VisitTaskSourceRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<ItemSource> find(Long itemId) {
    return jdbcClient
        .sql(
            "SELECT si.id, si.office_id, si.coordination_id, vc.manager_membership_id,"
                + " vc.customer_id, vcp.property_id, vcp.property_office_id, p.name,"
                + " si.scheduled_start_at, si.result FROM visit_schedule_item si JOIN"
                + " visit_coordination vc ON vc.id = si.coordination_id JOIN"
                + " visit_candidate_property vcp ON vcp.id = si.candidate_property_id JOIN property"
                + " p ON p.id = vcp.property_id WHERE si.id = :id")
        .param("id", itemId)
        .query(
            (rs, rowNum) ->
                new ItemSource(
                    rs.getLong("id"),
                    rs.getLong("office_id"),
                    rs.getLong("coordination_id"),
                    rs.getLong("manager_membership_id"),
                    rs.getLong("customer_id"),
                    rs.getLong("property_id"),
                    rs.getLong("property_office_id"),
                    rs.getString("name"),
                    TaskRepository.instant(rs, "scheduled_start_at"),
                    rs.getString("result")))
        .optional();
  }

  public List<Long> scheduledItems(Long coordinationId) {
    return jdbcClient
        .sql(
            "SELECT id FROM visit_schedule_item WHERE coordination_id = :coordination AND result"
                + " = 'SCHEDULED' ORDER BY id")
        .param("coordination", coordinationId)
        .query(Long.class)
        .list();
  }

  public Optional<CoordinationHead> coordinationHead(Long coordinationId) {
    return jdbcClient
        .sql("SELECT office_id, manager_membership_id FROM visit_coordination WHERE id = :id")
        .param("id", coordinationId)
        .query(
            (rs, rowNum) ->
                new CoordinationHead(rs.getLong("office_id"), rs.getLong("manager_membership_id")))
        .optional();
  }
}
