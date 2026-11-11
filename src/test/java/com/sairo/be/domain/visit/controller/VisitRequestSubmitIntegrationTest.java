package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitRequestIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class VisitRequestSubmitIntegrationTest extends VisitRequestIntegrationTestBase {

  @Test
  void 신청을_제출하면_신청_대기가_되고_이력과_조율_상태가_남는다() throws Exception {
    RequestFixture f = requestFixture();

    JsonNode response =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        requestUrl(f.coordinationId()),
                        requestBody(
                            f.candidateId(),
                            today().plusDays(3),
                            "10:00",
                            "10:30",
                            "30",
                            ",\"partySize\":2,\"requestNote\":\"주차 가능 여부를 확인하고 싶습니다\""),
                        f.cookie()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestKind").value("NEW"))
                .andExpect(jsonPath("$.crossOffice").value(false))
                .andExpect(jsonPath("$.accessGrantId").doesNotExist())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.partySize").value(2))
                .andExpect(jsonPath("$.requestNote").value("주차 가능 여부를 확인하고 싶습니다"))
                .andExpect(jsonPath("$.propertyOfficeId").value(f.owner().office().getId()))
                .andExpect(jsonPath("$.applicantOfficeId").value(f.owner().office().getId()))
                .andExpect(
                    jsonPath("$.applicantMembershipId").value(f.owner().membership().getId()))
                .andExpect(jsonPath("$.approvalId").value(f.approvalId()))
                .andExpect(jsonPath("$.conditionId").value(activeConditionId(f.approvalId())))
                .andExpect(jsonPath("$.candidatePropertyId").value(f.candidateId()))
                .andExpect(jsonPath("$.coordinationId").value(f.coordinationId()))
                .andExpect(jsonPath("$.closedAt").doesNotExist())
                .andReturn());

    long requestId = response.get("requestId").longValue();
    Map<String, Object> history =
        jdbcTemplate.queryForMap(
            "SELECT from_status, to_status, source, actor_membership_id FROM"
                + " visit_request_status_history WHERE request_id = ?",
            requestId);
    assertThat(history.get("from_status")).isNull();
    assertThat(history.get("to_status")).isEqualTo("PENDING");
    assertThat(history.get("source")).isEqualTo("BROKER");
    assertThat(history.get("actor_membership_id")).isEqualTo(f.owner().membership().getId());
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("APPLYING");
    Integer coordinationHistory =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_coordination_status_history WHERE coordination_id = ? AND"
                + " from_status = 'FINAL_CONFIRMATION_REQUIRED' AND to_status = 'APPLYING'",
            Integer.class,
            f.coordinationId());
    assertThat(coordinationHistory).isEqualTo(1);
  }

  @Test
  void 같은_후보_매물에_신청_대기가_있으면_409이고_철회_뒤에는_다시_낼_수_있다() throws Exception {
    RequestFixture f = requestFixture();
    long first = submit(f);

    assertError(
        mockMvc.perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie())),
        ErrorCode.VISIT_REQUEST_DUPLICATE_ACTIVE);

    mockMvc
        .perform(jsonPostAs("/api/visit-requests/" + first + "/withdraw", "{}", f.cookie()))
        .andExpect(status().isOk());
    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie()))
        .andExpect(status().isCreated());
    Integer rows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_request WHERE candidate_property_id = ?",
            Integer.class,
            f.candidateId());
    assertThat(rows).isEqualTo(2);
  }

  @Test
  void 소속공인중개사는_신청할_수_있고_중개보조원은_403이다() throws Exception {
    RequestFixture f = requestFixture();
    var agent = staffIn(f.owner().office(), OfficeMembershipRole.AGENT);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);

    assertError(
        mockMvc.perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), cookieOf(deputy))),
        ErrorCode.FORBIDDEN);
    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), cookieOf(agent)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.applicantMembershipId").value(agent.membership().getId()));
  }

  @Test
  void 로그인이_없으면_401이고_다른_사무소_조율_건은_404다() throws Exception {
    RequestFixture f = requestFixture();
    var stranger = newStaff(OfficeMembershipRole.OWNER);

    assertError(
        mockMvc.perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(
            jsonPostAs(requestUrl(f.coordinationId()), validBody(f), cookieOf(stranger))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPostAs(requestUrl(987654L), validBody(f), f.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 신청을_받을_수_없는_조율_상태는_409다() throws Exception {
    RequestFixture f = requestFixture();
    for (String state :
        List.of(
            "TIME_COLLECTING",
            "COORDINATABLE",
            "CONFIRMED",
            "VISIT_COMPLETED",
            "VISIT_INCOMPLETE",
            "CANCELLED")) {
      setCoordinationStatus(f.coordinationId(), state);
      assertError(
          mockMvc.perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie())),
          ErrorCode.INVALID_TRANSITION);
    }
    Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM visit_request", Integer.class);
    assertThat(rows).isZero();
  }

  @Test
  void 신청_진행_중_조율_건에도_다른_후보를_신청할_수_있다() throws Exception {
    RequestFixture f = requestFixture();
    submit(f);
    ExtraCandidate second = addCandidateWithApproval(f);

    mockMvc
        .perform(
            jsonPostAs(
                requestUrl(f.coordinationId()),
                requestBody(second.candidateId(), today().plusDays(3), "11:00", "11:30"),
                f.cookie()))
        .andExpect(status().isCreated());
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("APPLYING");
  }

  @Test
  void 출입승인이_없는_후보는_422다() throws Exception {
    RequestFixture f = requestFixture(newStaff(OfficeMembershipRole.OWNER), false, false);

    assertError(
        mockMvc.perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie())),
        ErrorCode.ACCESS_APPROVAL_MISSING);
  }

  @Test
  void 철회됐거나_끝난_출입승인은_없는_것으로_본다() throws Exception {
    RequestFixture f = requestFixture();
    jdbcTemplate.update(
        "UPDATE property_access_approval SET revoked_at = now(), revocation_reason = '철회',"
            + " revocation_recorded_at = now(), revoked_by_user_id = ? WHERE id = ?",
        f.owner().user().getId(),
        f.approvalId());

    assertError(
        mockMvc.perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie())),
        ErrorCode.ACCESS_APPROVAL_MISSING);
  }

  @Test
  void 승인자_조건이_없으면_422이고_적용_기간이_끝났으면_409다() throws Exception {
    RequestFixture none = requestFixture(newStaff(OfficeMembershipRole.OWNER), true, false);
    assertError(
        mockMvc.perform(
            jsonPostAs(requestUrl(none.coordinationId()), validBody(none), none.cookie())),
        ErrorCode.APPROVER_CONDITION_MISSING);

    RequestFixture f = requestFixture();
    jdbcTemplate.update(
        "UPDATE visit_approver_condition SET applies_until = now() - interval '1 hour', applies_from"
            + " = now() - interval '2 days' WHERE approval_id = ?",
        f.approvalId());
    assertError(
        mockMvc.perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie())),
        ErrorCode.APPROVER_CONDITION_EXPIRED);
  }

  @Test
  void 승인자_조건의_가능_시간대를_벗어나면_위반_규칙을_담아_422다() throws Exception {
    RequestFixture f = requestFixture();

    mockMvc
        .perform(
            jsonPostAs(
                requestUrl(f.coordinationId()),
                requestBody(f.candidateId(), today().plusDays(3), "17:30", "18:30"),
                f.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("APPROVER_CONDITION_VIOLATED"))
        .andExpect(jsonPath("$.violatedRules").value(hasItem("WINDOW_MISMATCH")))
        .andExpect(jsonPath("$.violatedRules").value(hasItem("CUSTOMER_CONFLICT")));
  }

  @Test
  void 고객_가능_시간_밖이면_고객_충돌만_알린다() throws Exception {
    RequestFixture f = requestFixture();

    mockMvc
        .perform(
            jsonPostAs(
                requestUrl(f.coordinationId()),
                requestBody(f.candidateId(), today().plusDays(3), "16:30", "17:00"),
                f.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules.length()").value(1))
        .andExpect(jsonPath("$.violatedRules[0]").value("CUSTOMER_CONFLICT"));
    mockMvc
        .perform(
            jsonPostAs(
                requestUrl(f.coordinationId()),
                requestBody(f.candidateId(), today().plusDays(3), "15:30", "16:30"),
                f.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules[0]").value("CUSTOMER_CONFLICT"));
  }

  @Test
  void 한_번_방문_상한과_최소_사전_신청_시간을_넘기면_422다() throws Exception {
    RequestFixture f = requestFixture();
    jdbcTemplate.update(
        "UPDATE visit_approver_condition SET single_visit_max_minutes = 20 WHERE approval_id = ?",
        f.approvalId());
    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules[0]").value("SINGLE_VISIT_MAX"));

    jdbcTemplate.update(
        "UPDATE visit_approver_condition SET single_visit_max_minutes = NULL, min_lead_minutes ="
            + " 10000 WHERE approval_id = ?",
        f.approvalId());
    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules[0]").value("MIN_LEAD"));
  }

  @Test
  void 확정_방문과_겹치거나_하루_횟수를_넘기게_하면_422다() throws Exception {
    RequestFixture f = requestFixture();
    long customer = f.customer();
    long otherCandidate =
        insertCoordinationWithCandidate(f.ref(), customer, f.propertyId(), "CONFIRMED");
    insertConfirmedVisit(
        f.ref(),
        coordinationOfCandidate(otherCandidate),
        f.propertyId(),
        f.approvalId(),
        today().plusDays(3),
        10,
        11);

    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules").value(hasItem("PROPERTY_CONFLICT")));

    jdbcTemplate.update(
        "UPDATE visit_schedule_item SET scheduled_start_at = scheduled_start_at + interval '2"
            + " hours', scheduled_end_at = scheduled_end_at + interval '2 hours'");
    jdbcTemplate.update(
        "UPDATE visit_approver_condition SET max_visits_per_day = 1 WHERE approval_id = ?",
        f.approvalId());
    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules").value(hasItem("MAX_VISITS_PER_DAY")));
  }

  @Test
  void 입력_검증_실패는_400이다() throws Exception {
    RequestFixture f = requestFixture();
    String url = requestUrl(f.coordinationId());
    long c = f.candidateId();
    var day = today().plusDays(3);

    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url, requestBody(c, day, "10:00", "10:30", "30", ",\"extra\":1"), f.cookie())),
        "extra",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, "{\"candidatePropertyId\":" + c + "}", f.cookie())),
        "requestedStartAt",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, requestBody(c, day, "10:30", "10:00"), f.cookie())),
        "requestedEndAt",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, requestBody(c, day, "10:03", "10:30"), f.cookie())),
        "requestedStartAt",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(url, requestBody(c, today().minusDays(1), "10:00", "10:30"), f.cookie())),
        "requestedStartAt",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(url, requestBody(c, day, "10:00", "10:30", "0", ""), f.cookie())),
        "expectedDurationMinutes",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url, requestBody(c, day, "10:00", "10:30", "30", ",\"partySize\":0"), f.cookie())),
        "partySize",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url,
                requestBody(
                    c, day, "10:00", "10:30", "30", ",\"requestNote\":\"010-1234-5678로 연락\""),
                f.cookie())),
        "requestNote",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url,
                requestBody(c, day, "10:00", "10:30", "30", ",\"requestNote\":\"이수요 고객님 동행\""),
                f.cookie())),
        "requestNote",
        "INVALID_VALUE");
    Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM visit_request", Integer.class);
    assertThat(rows).isZero();
  }

  @Test
  void 다른_조율_건의_후보와_보관_매물은_거절한다() throws Exception {
    RequestFixture f = requestFixture();
    RequestFixture other = requestFixture(f.owner(), true, true);

    assertError(
        mockMvc.perform(
            jsonPostAs(
                requestUrl(f.coordinationId()),
                requestBody(other.candidateId(), today().plusDays(3), "10:00", "10:30"),
                f.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);

    archive(f.propertyId());
    assertError(
        mockMvc.perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie())),
        ErrorCode.PROPERTY_ARCHIVED);
  }

  @Test
  void 구성원_동시_신청_한도_40건을_넘기면_429다() throws Exception {
    RequestFixture f = requestFixture();
    for (int i = 0; i < 40; i++) {
      long candidate =
          insertCoordinationWithCandidate(f.ref(), f.customer(), f.propertyId(), "APPLYING");
      insertRequestRow(
          f.ref(),
          candidate,
          f.propertyId(),
          f.approvalId(),
          f.owner().office().getId(),
          "PENDING",
          "now() + interval '3 days'");
    }

    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("VISIT_REQUEST_QUOTA_EXCEEDED"))
        .andExpect(jsonPath("$.quotaScope").value("MEMBERSHIP"))
        .andExpect(jsonPath("$.limit").value(40))
        .andExpect(jsonPath("$.current").value(40));
  }

  @Test
  void 출입승인_동시_신청_한도_10건을_넘기면_다른_구성원에게도_429다() throws Exception {
    RequestFixture f = requestFixture();
    var agent = staffIn(f.owner().office(), OfficeMembershipRole.AGENT);
    for (int i = 0; i < 10; i++) {
      long candidate =
          insertCoordinationWithCandidate(f.ref(), f.customer(), f.propertyId(), "APPLYING");
      insertRequestRow(
          f.ref(),
          candidate,
          f.propertyId(),
          f.approvalId(),
          f.owner().office().getId(),
          "PENDING",
          "now() + interval '3 days'");
    }

    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), cookieOf(agent)))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("VISIT_REQUEST_QUOTA_EXCEEDED"))
        .andExpect(jsonPath("$.quotaScope").value("APPROVAL"))
        .andExpect(jsonPath("$.limit").value(10))
        .andExpect(jsonPath("$.current").doesNotExist());
  }

  @Test
  void 종결된_신청은_한도에_세지_않는다() throws Exception {
    RequestFixture f = requestFixture();
    for (String closed : List.of("WITHDRAWN", "EXPIRED", "TIME_CLOSED", "REVALIDATION_FAILED")) {
      long candidate =
          insertCoordinationWithCandidate(f.ref(), f.customer(), f.propertyId(), "APPLYING");
      for (int i = 0; i < 3; i++) {
        insertRequestRow(
            f.ref(),
            candidate,
            f.propertyId(),
            f.approvalId(),
            f.owner().office().getId(),
            closed,
            "now() + interval '3 days'");
      }
    }

    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie()))
        .andExpect(status().isCreated());
  }

  @Test
  void 제출_속도가_분당_20건을_넘으면_429다() throws Exception {
    RequestFixture f = requestFixture();
    Cookie cookie = f.cookie();
    String badBody = requestBody(f.candidateId(), today().plusDays(3), "17:30", "18:30");
    for (int i = 0; i < 20; i++) {
      mockMvc
          .perform(jsonPostAs(requestUrl(f.coordinationId()), badBody, cookie))
          .andExpect(status().isUnprocessableContent());
    }

    assertError(
        mockMvc.perform(jsonPostAs(requestUrl(f.coordinationId()), badBody, cookie)),
        ErrorCode.RATE_LIMITED);
  }

  @Test
  void 같은_후보에_동시에_제출하면_한_건만_만들어진다() throws Exception {
    RequestFixture f = requestFixture();
    var agent = staffIn(f.owner().office(), OfficeMembershipRole.AGENT);
    Cookie agentCookie = cookieOf(agent);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
    for (int i = 0; i < 4; i++) {
      Cookie cookie = i % 2 == 0 ? f.cookie() : agentCookie;
      results.add(
          pool.submit(
              () ->
                  mockMvc
                      .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), cookie))
                      .andReturn()
                      .getResponse()
                      .getStatus()));
    }
    int created = 0;
    int conflicts = 0;
    for (var result : results) {
      int code = result.get();
      if (code == 201) {
        created++;
      } else if (code == 409) {
        conflicts++;
      }
    }
    pool.shutdown();

    assertThat(created).isEqualTo(1);
    assertThat(conflicts).isEqualTo(3);
  }
}
