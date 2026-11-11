package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.service.TrustSummary;
import com.sairo.be.domain.visit.service.TrustSummary.Indicator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class ApproverSelectionRepository {

  public record PendingRow(
      Long id,
      String kind,
      Instant startAt,
      Instant endAt,
      int durationMinutes,
      Integer partySize,
      String note,
      Instant submittedAt,
      Long applicantMembershipId,
      Long applicantOfficeId,
      Long propertyOfficeId,
      Long coordinationId,
      long version) {}

  public record Outcome(
      Long selectionId,
      String result,
      String failureCode,
      String failureDetail,
      Instant checkedAt) {}

  private final JdbcClient jdbcClient;

  public ApproverSelectionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<PendingRow> pendingOfApproval(Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT id, request_kind, requested_start_at, requested_end_at,"
                + " expected_duration_minutes, party_size, request_note, submitted_at,"
                + " applicant_membership_id, applicant_office_id, property_office_id,"
                + " coordination_id, version FROM visit_request WHERE approval_id = :approval AND"
                + " status = 'PENDING' ORDER BY submitted_at, id")
        .param("approval", approvalId)
        .query(
            (rs, rowNum) -> {
              int party = rs.getInt("party_size");
              Integer partySize = rs.wasNull() ? null : party;
              return new PendingRow(
                  rs.getLong("id"),
                  rs.getString("request_kind"),
                  rs.getTimestamp("requested_start_at").toInstant(),
                  rs.getTimestamp("requested_end_at").toInstant(),
                  rs.getInt("expected_duration_minutes"),
                  partySize,
                  rs.getString("request_note"),
                  rs.getTimestamp("submitted_at").toInstant(),
                  rs.getLong("applicant_membership_id"),
                  rs.getLong("applicant_office_id"),
                  rs.getLong("property_office_id"),
                  rs.getLong("coordination_id"),
                  rs.getLong("version"));
            })
        .list();
  }

  public long previousCompleted(Long propertyId, Long membershipId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM visit_schedule_item si JOIN visit_request vr ON vr.id ="
                + " si.confirmed_request_id WHERE vr.property_id = :property AND"
                + " vr.applicant_membership_id = :member AND si.result = 'COMPLETED'")
        .param("property", propertyId)
        .param("member", membershipId)
        .query(Long.class)
        .single();
  }

  public long previousCancelled(Long propertyId, Long membershipId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM visit_schedule_item si JOIN visit_request vr ON vr.id ="
                + " si.confirmed_request_id WHERE vr.property_id = :property AND"
                + " vr.applicant_membership_id = :member AND si.result = 'CANCELLED' AND"
                + " vr.status = 'CANCELLED' AND COALESCE((SELECT h.cancel_reason_code FROM"
                + " visit_schedule_item_result_history h WHERE h.schedule_item_id = si.id AND"
                + " h.result = 'CANCELLED' ORDER BY h.id DESC LIMIT 1), 'USER_CANCEL') IN"
                + " ('USER_CANCEL', 'OTHER')")
        .param("property", propertyId)
        .param("member", membershipId)
        .query(Long.class)
        .single();
  }

  public Set<Long> preferredMembers(Long approvalId) {
    return new HashSet<>(
        jdbcClient
            .sql(
                "SELECT broker_membership_id FROM visit_preferred_broker WHERE approval_id ="
                    + " :approval AND released_at IS NULL")
            .param("approval", approvalId)
            .query(Long.class)
            .list());
  }

  public List<Long> grantedOfficeIds(Long approvalId, Instant now) {
    return jdbcClient
        .sql(
            "SELECT DISTINCT grantee_office_id FROM visit_property_apply_grant WHERE approval_id"
                + " = :approval AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at >"
                + " :now) ORDER BY grantee_office_id")
        .param("approval", approvalId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .list();
  }

  public Long insertSelection(
      Long requestId, Long linkId, int comparedCount, String note, Instant at) {
    return jdbcClient
        .sql(
            "INSERT INTO visit_request_selection (request_id, approver_link_id,"
                + " compared_request_count, selection_note, selected_at) VALUES (:request, :link,"
                + " :count, :note, :at) RETURNING id")
        .param("request", requestId)
        .param("link", linkId)
        .param("count", comparedCount)
        .param("note", note)
        .param("at", Timestamp.from(at))
        .query(Long.class)
        .single();
  }

  public void insertRevalidation(
      Long requestId,
      Long selectionId,
      Long conditionId,
      String result,
      String failureCode,
      String failureDetail,
      Instant at) {
    jdbcClient
        .sql(
            "INSERT INTO visit_request_revalidation (request_id, selection_id, condition_id,"
                + " result, failure_code, failure_detail, checked_at) VALUES (:request,"
                + " :selection, :condition, :result, :code, :detail, :at)")
        .param("request", requestId)
        .param("selection", selectionId)
        .param("condition", conditionId)
        .param("result", result)
        .param("code", failureCode)
        .param("detail", failureDetail)
        .param("at", Timestamp.from(at))
        .update();
  }

  public void insertSnapshot(
      Long selectionId,
      Long comparedRequestId,
      Long brokerMembershipId,
      boolean selected,
      boolean preferred,
      TrustSummary summary,
      Instant asOf) {
    Indicator punctuality = summary.indicators().get(0);
    Indicator contact = summary.indicators().get(1);
    Indicator asRequested = summary.indicators().get(2);
    boolean available = TrustSummary.AVAILABLE.equals(summary.state());
    jdbcClient
        .sql(
            "INSERT INTO visit_selection_trust_snapshot (selection_id, compared_request_id,"
                + " broker_membership_id, is_selected, is_preferred, policy_version, as_of,"
                + " window_months, state, overall_band, completed_visit_count,"
                + " evaluated_visit_count, distinct_approver_count, punctuality_answered_count,"
                + " punctuality_yes_count, punctuality_band, advance_contact_answered_count,"
                + " advance_contact_yes_count, advance_contact_band, as_requested_answered_count,"
                + " as_requested_yes_count, as_requested_band) VALUES (:selection, :compared,"
                + " :broker, :selected, :preferred, :policy, :asOf, :window, :state, :overall,"
                + " :completed, :evaluated, :distinct, :pa, :py, :pb, :ca, :cy, :cb, :aa, :ay,"
                + " :ab)")
        .param("selection", selectionId)
        .param("compared", comparedRequestId)
        .param("broker", brokerMembershipId)
        .param("selected", selected)
        .param("preferred", preferred)
        .param("policy", TrustSummary.POLICY_VERSION)
        .param("asOf", Timestamp.from(asOf))
        .param("window", TrustSummary.WINDOW_MONTHS)
        .param("state", summary.state())
        .param("overall", summary.overallBand())
        .param("completed", summary.completedVisitCount())
        .param("evaluated", summary.evaluatedVisitCount())
        .param("distinct", summary.distinctApproverCount())
        .param("pa", punctuality.answeredCount())
        .param("py", punctuality.yesCount())
        .param("pb", available ? punctuality.band() : null)
        .param("ca", contact.answeredCount())
        .param("cy", contact.yesCount())
        .param("cb", available ? contact.band() : null)
        .param("aa", asRequested.answeredCount())
        .param("ay", asRequested.yesCount())
        .param("ab", available ? asRequested.band() : null)
        .update();
  }

  public Optional<Outcome> priorOutcome(Long requestId, Long linkId) {
    return jdbcClient
        .sql(
            "SELECT s.id, v.result, v.failure_code, v.failure_detail, v.checked_at FROM"
                + " visit_request_selection s JOIN visit_request_revalidation v ON v.selection_id"
                + " = s.id WHERE s.request_id = :request AND s.approver_link_id = :link ORDER BY"
                + " s.id DESC LIMIT 1")
        .param("request", requestId)
        .param("link", linkId)
        .query(
            (rs, rowNum) ->
                new Outcome(
                    rs.getLong("id"),
                    rs.getString("result"),
                    rs.getString("failure_code"),
                    rs.getString("failure_detail"),
                    rs.getTimestamp("checked_at").toInstant()))
        .optional();
  }

  public List<Long> timeClosedBySelection(Long selectionId) {
    return jdbcClient
        .sql(
            "SELECT h.request_id FROM visit_request_status_history h WHERE h.to_status ="
                + " 'TIME_CLOSED' AND h.reason = :reason ORDER BY h.request_id")
        .param("reason", "선택 " + selectionId + "번 확정으로 시간 마감")
        .query(Long.class)
        .list();
  }
}
