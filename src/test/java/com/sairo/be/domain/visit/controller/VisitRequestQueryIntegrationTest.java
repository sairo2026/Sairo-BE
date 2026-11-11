package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitRequestIntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class VisitRequestQueryIntegrationTest extends VisitRequestIntegrationTestBase {

  private static String detailUrl(long requestId) {
    return "/api/visit-requests/" + requestId;
  }

  private static String withdrawUrl(long requestId) {
    return detailUrl(requestId) + "/withdraw";
  }

  @Test
  void 철회하면_종결되고_이력이_남고_조율_건은_새_신청을_받는_상태로_돌아간다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("APPLYING");

    mockMvc
        .perform(jsonPostAs(withdrawUrl(requestId), "{\"reason\":\"일정 변경\"}", f.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WITHDRAWN"))
        .andExpect(jsonPath("$.statusReason").value("일정 변경"))
        .andExpect(jsonPath("$.closedAt").exists())
        .andExpect(jsonPath("$.version").value(2));

    Map<String, Object> history =
        jdbcTemplate.queryForMap(
            "SELECT from_status, source, reason, actor_membership_id FROM"
                + " visit_request_status_history WHERE request_id = ? AND to_status ="
                + " 'WITHDRAWN'",
            requestId);
    assertThat(history.get("from_status")).isEqualTo("PENDING");
    assertThat(history.get("source")).isEqualTo("BROKER");
    assertThat(history.get("reason")).isEqualTo("일정 변경");
    assertThat(history.get("actor_membership_id")).isEqualTo(f.owner().membership().getId());
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    Integer coordinationHistory =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_coordination_status_history WHERE coordination_id = ? AND"
                + " from_status = 'APPLYING' AND to_status = 'FINAL_CONFIRMATION_REQUIRED' AND"
                + " source = 'STAFF'",
            Integer.class,
            f.coordinationId());
    assertThat(coordinationHistory).isEqualTo(1);
  }

  @Test
  void 다른_신청이_남아_있으면_조율_건은_신청_진행을_유지한다() throws Exception {
    RequestFixture f = requestFixture();
    long first = submit(f);
    ExtraCandidate second = addCandidateWithApproval(f);
    mockMvc
        .perform(
            jsonPostAs(
                requestUrl(f.coordinationId()),
                requestBody(second.candidateId(), today().plusDays(3), "11:00", "11:30"),
                f.cookie()))
        .andExpect(status().isCreated());

    mockMvc.perform(jsonPostAs(withdrawUrl(first), "", f.cookie())).andExpect(status().isOk());

    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("APPLYING");
  }

  @Test
  void 이미_종결된_신청의_철회는_409이고_버전이_다르면_버전_충돌이다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);

    mockMvc
        .perform(jsonPostAs(withdrawUrl(requestId), "{}", f.cookie()).header("If-Match", "7"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.currentVersion").value(1));
    assertThat(requestStatus(requestId)).isEqualTo("PENDING");

    mockMvc
        .perform(jsonPostAs(withdrawUrl(requestId), "{}", f.cookie()).header("If-Match", "1"))
        .andExpect(status().isOk());
    assertError(
        mockMvc.perform(jsonPostAs(withdrawUrl(requestId), "{}", f.cookie())),
        ErrorCode.VISIT_REQUEST_INVALID_STATE);
    for (String closed :
        new String[] {"EXPIRED", "TIME_CLOSED", "REVALIDATION_FAILED", "CONFIRMED"}) {
      long other =
          insertRequestRow(
              f.ref(),
              insertCoordinationWithCandidate(f.ref(), f.customer(), f.propertyId(), "APPLYING"),
              f.propertyId(),
              f.approvalId(),
              f.owner().office().getId(),
              closed,
              "now() + interval '3 days'");
      assertError(
          mockMvc.perform(jsonPostAs(withdrawUrl(other), "{}", f.cookie())),
          ErrorCode.VISIT_REQUEST_INVALID_STATE);
    }
  }

  @Test
  void 철회는_권한과_사무소_경계와_입력을_검증한다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    var stranger = newStaff(OfficeMembershipRole.OWNER);

    assertError(
        mockMvc.perform(jsonPostAs(withdrawUrl(requestId), "{}", null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPostAs(withdrawUrl(requestId), "{}", cookieOf(deputy))),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPostAs(withdrawUrl(requestId), "{}", cookieOf(stranger))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPostAs(withdrawUrl(98765L), "{}", f.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        mockMvc.perform(jsonPostAs(withdrawUrl(requestId), "{\"memo\":\"x\"}", f.cookie())),
        "memo",
        "NOT_ALLOWED");
    assertThat(requestStatus(requestId)).isEqualTo("PENDING");
  }

  @Test
  void 같은_사무소의_다른_공인중개사도_철회할_수_있다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);
    var agent = staffIn(f.owner().office(), OfficeMembershipRole.AGENT);

    mockMvc
        .perform(jsonPostAs(withdrawUrl(requestId), "{}", cookieOf(agent)))
        .andExpect(status().isOk());
    Long actor =
        jdbcTemplate.queryForObject(
            "SELECT actor_membership_id FROM visit_request_status_history WHERE request_id = ? AND"
                + " to_status = 'WITHDRAWN'",
            Long.class,
            requestId);
    assertThat(actor).isEqualTo(agent.membership().getId());
  }

  @Test
  void 현황_목록은_제출_시각_내림차순이고_상태로_거를_수_있다() throws Exception {
    RequestFixture f = requestFixture();
    long first = submit(f);
    mockMvc.perform(jsonPostAs(withdrawUrl(first), "{}", f.cookie())).andExpect(status().isOk());
    long second = submit(f);
    String url = requestUrl(f.coordinationId());

    mockMvc
        .perform(authedGet(url, f.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].requestId").value(second))
        .andExpect(jsonPath("$.items[0].status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].crossOffice").value(false))
        .andExpect(jsonPath("$.items[0].propertyId").value(f.propertyId()))
        .andExpect(jsonPath("$.items[0].propertyOfficeId").value(f.owner().office().getId()))
        .andExpect(jsonPath("$.items[0].version").value(1))
        .andExpect(jsonPath("$.items[1].requestId").value(first))
        .andExpect(jsonPath("$.items[1].status").value("WITHDRAWN"))
        .andExpect(jsonPath("$.items[1].closedAt").exists());
    mockMvc
        .perform(authedGet(url + "?status=WITHDRAWN", f.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].requestId").value(first));
    mockMvc
        .perform(authedGet(url + "?status=CONFIRMED", f.cookie()))
        .andExpect(jsonPath("$.items.length()").value(0));
    assertFieldError(
        mockMvc.perform(authedGet(url + "?status=UNKNOWN", f.cookie())), "status", "NOT_ALLOWED");
  }

  @Test
  void 현황_목록은_401_다른_사무소_404_배정_밖_중개보조원_403이다() throws Exception {
    RequestFixture f = requestFixture();
    submit(f);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    var stranger = newStaff(OfficeMembershipRole.OWNER);
    String url = requestUrl(f.coordinationId());

    assertError(mockMvc.perform(authedGet(url, null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(authedGet(url, cookieOf(stranger))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(authedGet(url, cookieOf(deputy))), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(authedGet(requestUrl(98765L), f.cookie())), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 상세는_상태_이력과_선택_재검증_기록을_담고_고객_정보는_없다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);
    issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());
    long linkId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_approver_property_link WHERE approval_id = ?",
            Long.class,
            f.approvalId());
    long selectionId =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_request_selection (request_id, approver_link_id,"
                + " compared_request_count) VALUES (?, ?, 2) RETURNING id",
            Long.class,
            requestId,
            linkId);
    jdbcTemplate.update(
        "INSERT INTO visit_request_revalidation (request_id, selection_id, condition_id, result,"
            + " failure_code, failure_detail) VALUES (?, ?, ?, 'FAILED', 'MIN_GAP', '앞선 방문과의"
            + " 간격이 부족합니다')",
        requestId,
        selectionId,
        activeConditionId(f.approvalId()));

    String json =
        mockMvc
            .perform(authedGet(detailUrl(requestId), f.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value(requestId))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.statusHistory.length()").value(1))
            .andExpect(jsonPath("$.statusHistory[0].toStatus").value("PENDING"))
            .andExpect(jsonPath("$.statusHistory[0].fromStatus").doesNotExist())
            .andExpect(jsonPath("$.statusHistory[0].source").value("BROKER"))
            .andExpect(jsonPath("$.selections.length()").value(1))
            .andExpect(jsonPath("$.selections[0].selectionId").value(selectionId))
            .andExpect(jsonPath("$.selections[0].comparedRequestCount").value(2))
            .andExpect(jsonPath("$.revalidations.length()").value(1))
            .andExpect(jsonPath("$.revalidations[0].selectionId").value(selectionId))
            .andExpect(jsonPath("$.revalidations[0].result").value("FAILED"))
            .andExpect(jsonPath("$.revalidations[0].failureCode").value("MIN_GAP"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json).doesNotContain("이수요", "customerName", "phone");
  }

  @Test
  void 상세는_401_다른_사무소_404_배정_밖_중개보조원_403이다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    var stranger = newStaff(OfficeMembershipRole.OWNER);

    assertError(mockMvc.perform(authedGet(detailUrl(requestId), null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet(detailUrl(requestId), cookieOf(stranger))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet(detailUrl(requestId), cookieOf(deputy))), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(authedGet(detailUrl(98765L), f.cookie())), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 알림_목록은_본인_수신분만_최신순이고_읽지_않은_것만_거를_수_있다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);
    var agent = staffIn(f.owner().office(), OfficeMembershipRole.AGENT);
    long membership = f.owner().membership().getId();
    long older = insertNotice(requestId, membership, "TIME_CLOSED", "now() - interval '2 hours'");
    long newer = insertNotice(requestId, membership, "CONFIRMED", "now() - interval '1 hours'");
    insertNotice(requestId, agent.membership().getId(), "SELECTED", "now()");
    String url = "/api/visit-request-notices";

    mockMvc
        .perform(authedGet(url, f.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].noticeId").value(newer))
        .andExpect(jsonPath("$.items[0].noticeType").value("CONFIRMED"))
        .andExpect(jsonPath("$.items[0].requestId").value(requestId))
        .andExpect(jsonPath("$.items[0].coordinationId").value(f.coordinationId()))
        .andExpect(jsonPath("$.items[0].channel").doesNotExist())
        .andExpect(jsonPath("$.items[0].readAt").doesNotExist())
        .andExpect(jsonPath("$.items[1].noticeId").value(older));

    mockMvc
        .perform(jsonPostAs(url + "/" + newer + "/read", "", f.cookie()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(authedGet(url + "?unread=true", f.cookie()))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].noticeId").value(older));
    mockMvc.perform(authedGet(url, f.cookie())).andExpect(jsonPath("$.items[0].readAt").exists());
  }

  @Test
  void 알림_읽음_처리는_멱등이고_남의_알림과_없는_알림은_404다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);
    var agent = staffIn(f.owner().office(), OfficeMembershipRole.AGENT);
    var stranger = newStaff(OfficeMembershipRole.OWNER);
    long mine = insertNotice(requestId, f.owner().membership().getId(), "SELECTED", "now()");
    long others = insertNotice(requestId, agent.membership().getId(), "SELECTED", "now()");
    String readUrl = "/api/visit-request-notices/" + mine + "/read";

    assertError(mockMvc.perform(jsonPostAs(readUrl, "", null)), ErrorCode.UNAUTHENTICATED);
    mockMvc.perform(jsonPostAs(readUrl, "", f.cookie())).andExpect(status().isNoContent());
    java.sql.Timestamp firstRead =
        jdbcTemplate.queryForObject(
            "SELECT read_at FROM visit_request_notice WHERE id = ?",
            java.sql.Timestamp.class,
            mine);
    mockMvc.perform(jsonPostAs(readUrl, "", f.cookie())).andExpect(status().isNoContent());
    java.sql.Timestamp secondRead =
        jdbcTemplate.queryForObject(
            "SELECT read_at FROM visit_request_notice WHERE id = ?",
            java.sql.Timestamp.class,
            mine);
    assertThat(secondRead).isEqualTo(firstRead);
    assertError(
        mockMvc.perform(
            jsonPostAs("/api/visit-request-notices/" + others + "/read", "", f.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPostAs(readUrl, "", cookieOf(stranger))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPostAs("/api/visit-request-notices/98765/read", "", f.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet("/api/visit-request-notices", null)), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 알림_목록은_중개보조원도_본인_수신분을_볼_수_있다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    insertNotice(requestId, deputy.membership().getId(), "GRANT_REVOKED", "now()");

    JsonNode body =
        AuthFlowSupport.json(
            mockMvc
                .perform(authedGet("/api/visit-request-notices", cookieOf(deputy)))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(body.get("items").size()).isEqualTo(1);
  }

  private long insertNotice(long requestId, long membershipId, String type, String createdAt) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_request_notice (request_id, recipient_membership_id, notice_type,"
                + " created_at) VALUES (?, ?, ?, "
                + createdAt
                + ") RETURNING id",
            Long.class,
            requestId,
            membershipId,
            type);
    return id == null ? -1 : id;
  }
}
