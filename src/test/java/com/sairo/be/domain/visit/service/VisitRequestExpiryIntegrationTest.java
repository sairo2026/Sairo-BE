package com.sairo.be.domain.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitRequestIntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class VisitRequestExpiryIntegrationTest extends VisitRequestIntegrationTestBase {

  @Autowired private VisitRequestExpiryService expiryService;

  private long pending(
      RequestFixture f, long candidateId, long propertyId, long approvalId, String start) {
    return insertRequestRow(
        f.ref(), candidateId, propertyId, approvalId, f.owner().office().getId(), "PENDING", start);
  }

  @Test
  void 희망_시작_시각이_지난_신청_대기는_만료되고_시스템_이력과_조율_상태가_남는다() throws Exception {
    RequestFixture f = requestFixture();
    setCoordinationStatus(f.coordinationId(), "APPLYING");
    long requestId =
        pending(f, f.candidateId(), f.propertyId(), f.approvalId(), "now() - interval '1 hour'");

    int expired = expiryService.expireOverdue();

    assertThat(expired).isEqualTo(1);
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT status, status_reason, closed_at FROM visit_request WHERE id = ?", requestId);
    assertThat(row.get("status")).isEqualTo("EXPIRED");
    assertThat(row.get("status_reason")).isEqualTo("START_PASSED");
    assertThat(row.get("closed_at")).isNotNull();
    Map<String, Object> history =
        jdbcTemplate.queryForMap(
            "SELECT from_status, source, reason, actor_membership_id FROM"
                + " visit_request_status_history WHERE request_id = ? AND to_status = 'EXPIRED'",
            requestId);
    assertThat(history.get("from_status")).isEqualTo("PENDING");
    assertThat(history.get("source")).isEqualTo("SYSTEM");
    assertThat(history.get("reason")).isEqualTo("START_PASSED");
    assertThat(history.get("actor_membership_id")).isNull();
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    Integer coordinationHistory =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_coordination_status_history WHERE coordination_id = ? AND"
                + " to_status = 'FINAL_CONFIRMATION_REQUIRED' AND source = 'SYSTEM'",
            Integer.class,
            f.coordinationId());
    assertThat(coordinationHistory).isEqualTo(1);
    Integer notices =
        jdbcTemplate.queryForObject("SELECT count(*) FROM visit_request_notice", Integer.class);
    assertThat(notices).isZero();
  }

  @Test
  void 승인자_조건_적용_기간이_끝난_신청_대기는_만료된다() throws Exception {
    RequestFixture f = requestFixture();
    setCoordinationStatus(f.coordinationId(), "APPLYING");
    long requestId =
        pending(f, f.candidateId(), f.propertyId(), f.approvalId(), "now() + interval '3 days'");
    jdbcTemplate.update(
        "UPDATE visit_approver_condition SET applies_from = now() - interval '2 days',"
            + " applies_until = now() - interval '1 hour' WHERE approval_id = ?",
        f.approvalId());

    assertThat(expiryService.expireOverdue()).isEqualTo(1);

    assertThat(requestStatus(requestId)).isEqualTo("EXPIRED");
    String reason =
        jdbcTemplate.queryForObject(
            "SELECT status_reason FROM visit_request WHERE id = ?", String.class, requestId);
    assertThat(reason).isEqualTo("CONDITION_EXPIRED");
  }

  @Test
  void 유효한_신청과_종결된_신청은_건드리지_않고_다른_신청이_남으면_조율_상태를_유지한다() throws Exception {
    RequestFixture f = requestFixture();
    ExtraCandidate second = addCandidateWithApproval(f);
    setCoordinationStatus(f.coordinationId(), "APPLYING");
    long overdue =
        pending(f, f.candidateId(), f.propertyId(), f.approvalId(), "now() - interval '1 hour'");
    long valid =
        pending(
            f,
            second.candidateId(),
            second.propertyId(),
            second.approvalId(),
            "now() + interval '3 days'");
    long closed =
        insertRequestRow(
            f.ref(),
            insertCoordinationWithCandidate(f.ref(), f.customer(), f.propertyId(), "APPLYING"),
            f.propertyId(),
            f.approvalId(),
            f.owner().office().getId(),
            "WITHDRAWN",
            "now() - interval '1 hour'");

    assertThat(expiryService.expireOverdue()).isEqualTo(1);

    assertThat(requestStatus(overdue)).isEqualTo("EXPIRED");
    assertThat(requestStatus(valid)).isEqualTo("PENDING");
    assertThat(requestStatus(closed)).isEqualTo("WITHDRAWN");
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("APPLYING");
    assertThat(expiryService.expireOverdue()).isZero();
  }

  @Test
  void 신청_허용이_회수되거나_끝난_다른_사무소_신청은_만료되고_알림을_남긴다() throws Exception {
    RequestFixture owner = requestFixture();
    RequestFixture grantee = requestFixture();
    long candidate =
        insertCoordinationWithCandidate(
            grantee.ref(),
            grantee.customer(),
            owner.propertyId(),
            owner.owner().office().getId(),
            "APPLYING");
    long revokedGrant = insertGrant(owner, grantee, "revoked");
    long expiredGrant = insertGrant(owner, grantee, "expired");
    long revoked = insertCrossRequest(owner, grantee, candidate, revokedGrant);
    long secondCandidate =
        insertCoordinationWithCandidate(
            grantee.ref(),
            grantee.customer(),
            owner.propertyId(),
            owner.owner().office().getId(),
            "APPLYING");
    long expired = insertCrossRequest(owner, grantee, secondCandidate, expiredGrant);

    assertThat(expiryService.expireOverdue()).isEqualTo(2);

    assertThat(requestStatus(revoked)).isEqualTo("EXPIRED");
    assertThat(requestStatus(expired)).isEqualTo("EXPIRED");
    assertThat(statusReason(revoked)).isEqualTo("GRANT_REVOKED");
    assertThat(statusReason(expired)).isEqualTo("GRANT_EXPIRED");
    Integer notices =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_request_notice WHERE notice_type = 'GRANT_REVOKED' AND"
                + " recipient_membership_id = ?",
            Integer.class,
            grantee.owner().membership().getId());
    assertThat(notices).isEqualTo(2);
  }

  private String statusReason(long requestId) {
    return jdbcTemplate.queryForObject(
        "SELECT status_reason FROM visit_request WHERE id = ?", String.class, requestId);
  }

  private long insertGrant(RequestFixture owner, RequestFixture grantee, String kind) {
    long ownerOffice = owner.owner().office().getId();
    long granteeOffice = grantee.owner().office().getId();
    long ownerMember = owner.owner().membership().getId();
    if ("revoked".equals(kind)) {
      jdbcTemplate.update(
          "INSERT INTO visit_property_apply_grant (approval_id, property_id, owner_office_id,"
              + " grantee_office_id, granted_by_membership_id, revoked_at, revoke_source,"
              + " revoked_by_membership_id, revoke_reason) VALUES (?, ?, ?, ?, ?, now(),"
              + " 'OFFICE', ?, '회수')",
          owner.approvalId(),
          owner.propertyId(),
          ownerOffice,
          granteeOffice,
          ownerMember,
          ownerMember);
    } else {
      jdbcTemplate.update(
          "INSERT INTO visit_property_apply_grant (approval_id, property_id, owner_office_id,"
              + " grantee_office_id, granted_by_membership_id, granted_at, expires_at) VALUES (?,"
              + " ?, ?, ?, ?, now() - interval '1 day', now() - interval '1 minute')",
          owner.approvalId(),
          owner.propertyId(),
          ownerOffice,
          granteeOffice,
          ownerMember);
    }
    return jdbcTemplate.queryForObject(
        "SELECT max(id) FROM visit_property_apply_grant", Long.class);
  }

  private long insertCrossRequest(
      RequestFixture owner, RequestFixture grantee, long candidateId, long grantId) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_request (condition_id, approval_id, property_id,"
                + " applicant_office_id, applicant_membership_id, coordination_id,"
                + " candidate_property_id, requested_start_at, requested_end_at,"
                + " expected_duration_minutes, status, property_office_id, access_grant_id) VALUES"
                + " (?, ?, ?, ?, ?, ?, ?, now() + interval '3 days', now() + interval '3 days' +"
                + " interval '30 minutes', 30, 'PENDING', ?, ?) RETURNING id",
            Long.class,
            activeConditionId(owner.approvalId()),
            owner.approvalId(),
            owner.propertyId(),
            grantee.owner().office().getId(),
            grantee.owner().membership().getId(),
            coordinationOfCandidate(candidateId),
            candidateId,
            owner.owner().office().getId(),
            grantId);
    return id == null ? -1 : id;
  }
}
