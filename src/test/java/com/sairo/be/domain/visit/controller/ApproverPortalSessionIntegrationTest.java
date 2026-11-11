package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ApproverAccountTestBase;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ApproverPortalSessionIntegrationTest extends ApproverAccountTestBase {

  @Test
  void 승인자_세션으로_조건을_등록하면_입력한_프로필을_남긴다() throws Exception {
    RequestFixture f = requestFixture();
    Cookie cookie = claim(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()));
    String base = portalApproval(f.approvalId());

    portalGet(base + "/conditions", cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalId").value(f.approvalId()))
        .andExpect(jsonPath("$.source").value("PROXY"));
    mockMvc
        .perform(jsonPostAs(base + "/conditions/impact-preview", conditionJson(false), cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasImpact").value(false));
    mockMvc
        .perform(jsonPutAs(base + "/conditions", conditionJson(false), cookie, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("DIRECT"))
        .andExpect(jsonPath("$.versionNo").value(2))
        .andExpect(jsonPath("$.enteredViaProfile").value(true));

    assertThat(
            count(
                "SELECT count(*) FROM visit_approver_condition WHERE approval_id = ? AND status ="
                    + " 'ACTIVE' AND entered_by_profile_id = ?",
                f.approvalId(),
                profileId()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM consent_guest_notice_ack WHERE link_kind = 'APPROVER_PORTAL'"
                    + " AND action_type = 'CONDITION_SAVE' AND approver_profile_id = ?",
                profileId()))
        .isEqualTo(1);
  }

  @Test
  void 승인자_세션으로_신청을_비교하고_선택하면_프로필이_선택_기록에_남고_확정_일정을_본다() throws Exception {
    SelectionFixture s = selectionFixture();
    RequestFixture f = s.base();
    Cookie cookie = claim(s.token());
    String base = portalApproval(f.approvalId());

    portalGet(base + "/visit-requests", cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].requestId").value(s.requestId()));
    MockHttpServletRequestBuilder select =
        jsonPostAs(
            base + "/visit-requests/" + s.requestId() + "/select",
            selectBody(s.requestId()),
            cookie);
    select.header("If-Match", String.valueOf(requestVersion(s.requestId())));
    mockMvc
        .perform(select)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_selection WHERE request_id = ? AND"
                    + " approver_profile_id = ? AND approver_link_id IS NULL",
                s.requestId(),
                profileId()))
        .isEqualTo(1);

    portalGet(base + "/confirmed-schedules", cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].confirmedRequestId").value(s.requestId()))
        .andExpect(jsonPath("$.items[0].customerName").doesNotExist());
  }

  @Test
  void 공동_승인자_연결은_읽기만_되고_연결되지_않은_출입승인은_404이다() throws Exception {
    RequestFixture f = requestFixture();
    long coApproval = registerApproval(f.cookie(), f.propertyId(), "CO_APPROVER", -1, 30);
    Cookie cookie = claim(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()));
    mockMvc
        .perform(
            jsonPostAs(
                portalApproval(coApproval) + "/link-decision", "{\"decision\":\"ACCEPT\"}", cookie))
        .andExpect(status().isOk());
    String co = portalApproval(coApproval);

    portalGet(co + "/conditions", cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalId").value(f.approvalId()));
    portalGet(co + "/confirmed-schedules", cookie).andExpect(status().isOk());
    portalGet(co + "/visit-reviews", cookie).andExpect(status().isOk());
    portalGet(co + "/preferred-brokers", cookie).andExpect(status().isOk());
    assertError(
        mockMvc.perform(jsonPutAs(co + "/conditions", conditionJson(false), cookie, null)),
        ErrorCode.APPROVER_READ_ONLY);
    assertError(portalGet(co + "/visit-requests", cookie), ErrorCode.APPROVER_READ_ONLY);
    assertError(
        mockMvc.perform(jsonPostAs(co + "/preferred-brokers", "{\"sourceRequestId\":1}", cookie)),
        ErrorCode.APPROVER_READ_ONLY);

    String other = portalApproval(otherPropertyApproval(f, null));
    assertError(portalGet(other + "/conditions", cookie), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(portalGet(other + "/visit-requests", cookie), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(portalGet(other + "/confirmed-schedules", cookie), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(portalGet(other + "/visit-reviews", cookie), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(portalGet(other + "/preferred-brokers", cookie), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPostAs(other + "/visit-requests/1/select", selectBody(1), cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 승인자_세션_경로는_세션이_없으면_401이다() throws Exception {
    String base = portalApproval(1);
    assertError(portalGet(base + "/conditions", null), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPutAs(base + "/conditions", "{}", null, null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPostAs(base + "/conditions/impact-preview", "{}", null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(portalGet(base + "/visit-requests", null), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPostAs(base + "/visit-requests/1/select", "{}", null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(portalGet(base + "/confirmed-schedules", null), ErrorCode.UNAUTHENTICATED);
    assertError(portalGet(base + "/visit-reviews", null), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPutAs(base + "/visit-reviews/1", "{}", null, null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPostAs(base + "/visit-reviews/1/withdraw", "", null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(portalGet(base + "/preferred-brokers", null), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPostAs(base + "/preferred-brokers", "{}", null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPostAs(base + "/preferred-brokers/1/release", "", null)),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 승인자_세션으로_평가와_선호_중개사를_입력하면_프로필로_기록한다() throws Exception {
    CompletedVisit v = completedVisit();
    long approvalId = v.s().base().approvalId();
    Cookie cookie = claim(v.token());
    String base = portalApproval(approvalId);

    portalGet(base + "/visit-reviews", cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].scheduleItemId").value(v.itemId()))
        .andExpect(jsonPath("$.items[0].editable").value(true));
    mockMvc
        .perform(
            jsonPutAs(
                base + "/visit-reviews/" + v.itemId(),
                reviewBody("YES", "NO", "SKIP"),
                cookie,
                null))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.reviewStatus").value("SUBMITTED"));
    assertThat(
            count(
                "SELECT count(*) FROM visit_review WHERE schedule_item_id = ? AND"
                    + " approver_profile_id = ? AND approver_link_id IS NULL",
                v.itemId(),
                profileId()))
        .isEqualTo(1);
    long version = count("SELECT version FROM visit_review WHERE schedule_item_id = ?", v.itemId());
    MockHttpServletRequestBuilder withdraw =
        jsonPostAs(base + "/visit-reviews/" + v.itemId() + "/withdraw", "", cookie);
    withdraw.header("If-Match", String.valueOf(version));
    mockMvc
        .perform(withdraw)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewStatus").value("WITHDRAWN"));

    JsonNode designated =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        base + "/preferred-brokers",
                        "{\"sourceRequestId\":"
                            + v.s().requestId()
                            + ",\"acknowledgedNoticeVersion\":\""
                            + NOTICE_VERSION
                            + "\"}",
                        cookie))
                .andExpect(status().isCreated())
                .andReturn());
    long preferredId = designated.get("preferredBrokerId").longValue();
    assertThat(
            count(
                "SELECT count(*) FROM visit_preferred_broker WHERE id = ? AND"
                    + " designated_via_profile_id = ?",
                preferredId,
                profileId()))
        .isEqualTo(1);
    mockMvc
        .perform(jsonPostAs(base + "/preferred-brokers/" + preferredId + "/release", "", cookie))
        .andExpect(status().isOk());
    assertThat(
            count(
                "SELECT count(*) FROM visit_preferred_broker WHERE id = ? AND"
                    + " released_via_profile_id = ?",
                preferredId,
                profileId()))
        .isEqualTo(1);
    portalGet(base + "/preferred-brokers", cookie).andExpect(status().isOk());
  }
}
