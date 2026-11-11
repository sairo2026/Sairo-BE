package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.ApplyGrantPort;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitRequestIntegrationTestBase;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@IntegrationTest
class VisitRequestCrossOfficeIntegrationTest extends VisitRequestIntegrationTestBase {

  @MockitoBean private ApplyGrantPort applyGrantPort;

  private record Cross(
      RequestFixture owner, RequestFixture grantee, long coordinationId, long candidateId) {}

  private Cross cross() throws Exception {
    RequestFixture owner = requestFixture();
    RequestFixture grantee = requestFixture(newStaff(OfficeMembershipRole.OWNER), false, false);
    long candidate =
        insertCoordinationWithCandidate(
            grantee.ref(),
            grantee.customer(),
            owner.propertyId(),
            owner.owner().office().getId(),
            "TIME_COLLECTING");
    long coordinationId = coordinationOfCandidate(candidate);
    submitCustomerSlots(grantee.cookie(), coordinationId, today().plusDays(3), 10, 16);
    setCoordinationStatus(coordinationId, "FINAL_CONFIRMATION_REQUIRED");
    return new Cross(owner, grantee, coordinationId, candidate);
  }

  private long insertGrant(Cross c) {
    jdbcTemplate.update(
        "INSERT INTO visit_property_apply_grant (approval_id, property_id, owner_office_id,"
            + " grantee_office_id, granted_by_membership_id) VALUES (?, ?, ?, ?, ?)",
        c.owner().approvalId(),
        c.owner().propertyId(),
        c.owner().owner().office().getId(),
        c.grantee().owner().office().getId(),
        c.owner().owner().membership().getId());
    return jdbcTemplate.queryForObject(
        "SELECT max(id) FROM visit_property_apply_grant", Long.class);
  }

  @Test
  void 허용을_받은_다른_사무소_매물에_신청하면_교차_사무소_신청으로_기록한다() throws Exception {
    Cross c = cross();
    long grantId = insertGrant(c);
    when(applyGrantPort.findActiveGrantId(any(), any(), any(), any()))
        .thenReturn(Optional.of(grantId));

    mockMvc
        .perform(
            jsonPostAs(
                requestUrl(c.coordinationId()),
                requestBody(c.candidateId(), today().plusDays(3), "10:00", "10:30"),
                c.grantee().cookie()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.crossOffice").value(true))
        .andExpect(jsonPath("$.accessGrantId").value(grantId))
        .andExpect(jsonPath("$.propertyOfficeId").value(c.owner().owner().office().getId()))
        .andExpect(jsonPath("$.applicantOfficeId").value(c.grantee().owner().office().getId()))
        .andExpect(jsonPath("$.approvalId").value(c.owner().approvalId()));
    assertThat(coordinationStatus(c.coordinationId())).isEqualTo("APPLYING");
  }

  @Test
  void 매물을_가진_사무소는_다른_사무소의_신청_상세와_현황을_볼_수_없다() throws Exception {
    Cross c = cross();
    long grantId = insertGrant(c);
    when(applyGrantPort.findActiveGrantId(any(), any(), any(), any()))
        .thenReturn(Optional.of(grantId));
    long requestId =
        com.sairo.be.support.AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            requestUrl(c.coordinationId()),
                            requestBody(c.candidateId(), today().plusDays(3), "10:00", "10:30"),
                            c.grantee().cookie()))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("requestId")
            .longValue();

    assertError(
        mockMvc.perform(authedGet("/api/visit-requests/" + requestId, c.owner().cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet(requestUrl(c.coordinationId()), c.owner().cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    mockMvc
        .perform(authedGet("/api/visit-requests/" + requestId, c.grantee().cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.crossOffice").value(true));
  }

  @Test
  void 허용이_없으면_403이고_저장하지_않는다() throws Exception {
    Cross c = cross();
    when(applyGrantPort.findActiveGrantId(any(), any(), any(), any())).thenReturn(Optional.empty());

    assertError(
        mockMvc.perform(
            jsonPostAs(
                requestUrl(c.coordinationId()),
                requestBody(c.candidateId(), today().plusDays(3), "10:00", "10:30"),
                c.grantee().cookie())),
        ErrorCode.APPLY_GRANT_INACTIVE);
    Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM visit_request", Integer.class);
    assertThat(rows).isZero();
  }

  @Test
  void 매물의_출입승인이_끝났으면_허용이_있어도_403이다() throws Exception {
    Cross c = cross();
    long grantId = insertGrant(c);
    when(applyGrantPort.findActiveGrantId(any(), any(), any(), any()))
        .thenReturn(Optional.of(grantId));
    jdbcTemplate.update(
        "UPDATE property_access_approval SET revoked_at = now(), revocation_reason = '철회',"
            + " revocation_recorded_at = now(), revoked_by_user_id = ? WHERE id = ?",
        c.owner().owner().user().getId(),
        c.owner().approvalId());

    assertError(
        mockMvc.perform(
            jsonPostAs(
                requestUrl(c.coordinationId()),
                requestBody(c.candidateId(), today().plusDays(3), "10:00", "10:30"),
                c.grantee().cookie())),
        ErrorCode.APPLY_GRANT_INACTIVE);
  }
}
