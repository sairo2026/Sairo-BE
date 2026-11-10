package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Visit;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class ConfirmedVisitRepository {

  private final JdbcClient jdbcClient;

  public ConfirmedVisitRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<Visit> findByApproval(Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT si.id, si.coordination_id, si.scheduled_start_at, si.scheduled_end_at,"
                + " si.result, vr.id AS request_id, vr.applicant_membership_id FROM"
                + " visit_schedule_item si JOIN visit_request vr ON vr.id = si.confirmed_request_id"
                + " WHERE vr.approval_id = :approval AND si.result IN ('SCHEDULED', 'COMPLETED',"
                + " 'NO_SHOW') AND si.scheduled_start_at IS NOT NULL AND si.scheduled_end_at IS"
                + " NOT NULL ORDER BY si.scheduled_start_at, si.id")
        .param("approval", approvalId)
        .query(
            (rs, rowNum) ->
                new Visit(
                    rs.getLong("id"),
                    rs.getLong("coordination_id"),
                    rs.getTimestamp("scheduled_start_at").toInstant(),
                    rs.getTimestamp("scheduled_end_at").toInstant(),
                    rs.getString("result"),
                    rs.getLong("request_id"),
                    rs.getLong("applicant_membership_id")))
        .list();
  }

  public void recordConditionChangeNotice(Long requestId, Long recipientMembershipId) {
    jdbcClient
        .sql(
            "INSERT INTO visit_request_notice (request_id, recipient_membership_id, notice_type)"
                + " SELECT :request, :recipient, 'CONDITION_CHANGE_IMPACT' WHERE NOT EXISTS"
                + " (SELECT 1 FROM visit_request_notice WHERE request_id = :request AND"
                + " notice_type = 'CONDITION_CHANGE_IMPACT' AND read_at IS NULL)")
        .param("request", requestId)
        .param("recipient", recipientMembershipId)
        .update();
  }
}
