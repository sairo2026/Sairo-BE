package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitReviewTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class VisitReviewDisputeIntegrationTest extends VisitReviewTestBase {

  private ResultActions trust(Cookie cookie) throws Exception {
    return mockMvc.perform(authedGet("/api/visit-trust/me", cookie));
  }

  private ResultActions received(Cookie cookie, String query) throws Exception {
    return mockMvc.perform(authedGet("/api/visit-reviews/received" + query, cookie));
  }

  @Test
  void 중개사는_자기_신뢰_요약을_조회하고_이의_중인_평가는_요약에서_빠진다() throws Exception {
    CompletedVisit v = completedVisit();
    trust(v.cookie())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policyVersion").value("TRUST_V1"))
        .andExpect(jsonPath("$.windowMonths").value(12))
        .andExpect(jsonPath("$.state").value("NO_DATA"))
        .andExpect(jsonPath("$.overallBand").value(nullValue()))
        .andExpect(jsonPath("$.completedVisitCount").value(1))
        .andExpect(jsonPath("$.evaluatedVisitCount").value(0))
        .andExpect(jsonPath("$.indicators.length()").value(3))
        .andExpect(jsonPath("$.indicators[0].indicator").value("PUNCTUALITY"))
        .andExpect(jsonPath("$.unconfirmedIndicators.length()").value(3));

    long reviewId = submitReview(v);
    trust(v.cookie())
        .andExpect(jsonPath("$.state").value("INSUFFICIENT"))
        .andExpect(jsonPath("$.evaluatedVisitCount").value(1))
        .andExpect(jsonPath("$.distinctApproverCount").value(1))
        .andExpect(jsonPath("$.indicators[0].answeredCount").value(1))
        .andExpect(jsonPath("$.indicators[0].yesCount").value(1))
        .andExpect(jsonPath("$.indicators[0].band").value(nullValue()))
        .andExpect(jsonPath("$.indicators[2].answeredCount").value(0))
        .andExpect(jsonPath("$.excludedReviewCount").value(0));

    fileDispute(v.cookie(), reviewId, disputeBody()).andExpect(status().isCreated());
    trust(v.cookie())
        .andExpect(jsonPath("$.state").value("NO_DATA"))
        .andExpect(jsonPath("$.evaluatedVisitCount").value(0))
        .andExpect(jsonPath("$.excludedReviewCount").value(1));

    TestData.Staff deputy = staffIn(v.s().base().owner().office(), OfficeMembershipRole.DEPUTY);
    assertError(trust(cookieOf(deputy)), ErrorCode.FORBIDDEN);
    assertError(trust(null), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 받은_평가_목록은_본인과_대표의_사무소_범위로_나뉜다() throws Exception {
    CompletedVisit v = completedVisit();
    long reviewId = submitReview(v);

    received(v.cookie(), "")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].reviewId").value(reviewId))
        .andExpect(jsonPath("$.items[0].scheduleItemId").value(v.itemId()))
        .andExpect(jsonPath("$.items[0].reviewStatus").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].answers.punctuality").value("YES"))
        .andExpect(jsonPath("$.items[0].propertyName").isNotEmpty())
        .andExpect(jsonPath("$.items[0].reviewedBrokerName").isNotEmpty())
        .andExpect(jsonPath("$.items[0].actualVisitedAt").isNotEmpty())
        .andExpect(jsonPath("$.items[0].canDispute").value(true))
        .andExpect(jsonPath("$.items[0].dispute").value(nullValue()));
    received(v.cookie(), "?scope=OFFICE&status=ACTIVE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    received(v.cookie(), "?status=DISPUTED").andExpect(jsonPath("$.totalElements").value(0));

    TestData.Staff agent = staffIn(v.s().base().owner().office(), OfficeMembershipRole.AGENT);
    received(cookieOf(agent), "").andExpect(jsonPath("$.totalElements").value(0));
    assertError(received(cookieOf(agent), "?scope=OFFICE"), ErrorCode.FORBIDDEN);
    TestData.Staff deputy = staffIn(v.s().base().owner().office(), OfficeMembershipRole.DEPUTY);
    assertError(received(cookieOf(deputy), ""), ErrorCode.FORBIDDEN);
    assertFieldError(received(v.cookie(), "?scope=ALL"), "scope", "NOT_ALLOWED");
    assertFieldError(received(v.cookie(), "?status=WITHDRAWN"), "status", "NOT_ALLOWED");
    assertError(received(null, ""), ErrorCode.UNAUTHENTICATED);
    TestData.Staff stranger = newStaff(OfficeMembershipRole.OWNER);
    received(cookieOf(stranger), "?scope=OFFICE").andExpect(jsonPath("$.totalElements").value(0));

    withdrawReview(v.token(), v.itemId(), 1).andExpect(status().isOk());
    received(v.cookie(), "").andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void 평가받은_중개사는_한_번_이의를_내고_승인자_화면은_잠긴다() throws Exception {
    CompletedVisit v = completedVisit();
    long reviewId = submitReview(v);

    TestData.Staff deputy = staffIn(v.s().base().owner().office(), OfficeMembershipRole.DEPUTY);
    assertError(fileDispute(cookieOf(deputy), reviewId, disputeBody()), ErrorCode.FORBIDDEN);
    TestData.Staff agent = staffIn(v.s().base().owner().office(), OfficeMembershipRole.AGENT);
    assertError(
        fileDispute(cookieOf(agent), reviewId, disputeBody()), ErrorCode.RESOURCE_NOT_FOUND);
    TestData.Staff stranger = newStaff(OfficeMembershipRole.OWNER);
    assertError(
        fileDispute(cookieOf(stranger), reviewId, disputeBody()), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(fileDispute(null, reviewId, disputeBody()), ErrorCode.UNAUTHENTICATED);
    assertFieldError(
        fileDispute(v.cookie(), reviewId, "{\"reasonCode\":\"ANGRY\",\"reasonText\":\"사유\"}"),
        "reasonCode",
        "NOT_ALLOWED");
    assertFieldError(
        fileDispute(v.cookie(), reviewId, "{\"reasonCode\":\"OTHER\"}"), "reasonText", "REQUIRED");
    assertError(
        fileDispute(
            v.cookie(),
            reviewId,
            "{\"reasonCode\":\"OTHER\",\"reasonText\":\"주민번호 900101-1234567 확인\"}"),
        ErrorCode.SENSITIVE_DATA_DETECTED);
    assertError(fileDispute(v.cookie(), 987654L, disputeBody()), ErrorCode.RESOURCE_NOT_FOUND);

    fileDispute(v.cookie(), reviewId, disputeBody())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.reviewId").value(reviewId))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.reasonCode").value("FACT_INCORRECT"))
        .andExpect(jsonPath("$.reviewStatus").value("DISPUTED"))
        .andExpect(jsonPath("$.targetDecisionAt").isNotEmpty());
    assertError(
        fileDispute(v.cookie(), reviewId, disputeBody()), ErrorCode.VISIT_REVIEW_DISPUTE_EXISTS);

    received(v.cookie(), "")
        .andExpect(jsonPath("$.items[0].reviewStatus").value("DISPUTED"))
        .andExpect(jsonPath("$.items[0].canDispute").value(false))
        .andExpect(jsonPath("$.items[0].dispute.status").value("OPEN"));
    String list =
        reviewList(v.token())
            .andExpect(jsonPath("$.items[0].reviewStatus").value("LOCKED"))
            .andExpect(jsonPath("$.items[0].editable").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(list).doesNotContain("FACT_INCORRECT").doesNotContain("dispute");
    assertError(
        putReview(v.token(), v.itemId(), reviewBody("YES", "YES", "YES"), 2),
        ErrorCode.VISIT_REVIEW_LOCKED);
    assertError(withdrawReview(v.token(), v.itemId(), 2), ErrorCode.VISIT_REVIEW_LOCKED);
  }

  @Test
  void 이의_기간이_지나거나_철회된_평가에는_이의를_낼_수_없다() throws Exception {
    CompletedVisit v = completedVisit();
    long reviewId = submitReview(v);
    jdbcTemplate.update(
        "UPDATE visit_review SET submitted_at = now() - interval '31 days', last_revised_at ="
            + " now() - interval '31 days' WHERE id = ?",
        reviewId);
    received(v.cookie(), "").andExpect(jsonPath("$.items[0].canDispute").value(false));
    assertError(
        fileDispute(v.cookie(), reviewId, disputeBody()),
        ErrorCode.VISIT_REVIEW_DISPUTE_WINDOW_CLOSED);

    CompletedVisit other = completedVisit();
    long otherReview = submitReview(other);
    withdrawReview(other.token(), other.itemId(), 1).andExpect(status().isOk());
    assertError(
        fileDispute(other.cookie(), otherReview, disputeBody()), ErrorCode.VISIT_REVIEW_LOCKED);
  }

  @Test
  void 운영자_지원_역할은_이의를_접수_순으로_보고_유지_결정한다() throws Exception {
    CompletedVisit v = completedVisit();
    long reviewId = submitReview(v);
    long disputeId =
        AuthFlowSupport.json(
                fileDispute(v.cookie(), reviewId, disputeBody())
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("disputeId")
            .longValue();
    Cookie support = operatorCookie(OperatorRole.SUPPORT);

    String body =
        mockMvc
            .perform(authedGet("/api/ops/visit-review-disputes", support))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].disputeId").value(disputeId))
            .andExpect(jsonPath("$.items[0].status").value("OPEN"))
            .andExpect(jsonPath("$.items[0].reasonText").isNotEmpty())
            .andExpect(jsonPath("$.items[0].dueAt").isNotEmpty())
            .andExpect(jsonPath("$.items[0].review.reviewStatus").value("DISPUTED"))
            .andExpect(jsonPath("$.items[0].review.approverType").isNotEmpty())
            .andExpect(jsonPath("$.items[0].review.issuerIsReviewedBroker").value(true))
            .andExpect(
                jsonPath("$.items[0].reviewedBroker.membershipId")
                    .value(v.s().base().owner().membership().getId()))
            .andExpect(jsonPath("$.items[0].reviewedBroker.officeName").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("propertyAddress").doesNotContain("approverPhone");
    assertNoScoreFields(new tools.jackson.databind.ObjectMapper().readTree(body));
    mockMvc
        .perform(authedGet("/api/ops/visit-review-disputes?status=UPHELD", support))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertFieldError(
        mockMvc.perform(authedGet("/api/ops/visit-review-disputes?status=DONE", support)),
        "status",
        "NOT_ALLOWED");

    String decisionUrl = "/api/ops/visit-review-disputes/" + disputeId + "/decision";
    assertError(
        mockMvc.perform(
            jsonPostAs(
                decisionUrl,
                "{\"decision\":\"UPHELD\",\"decisionNote\":\"기록이 맞다\"}",
                operatorCookie(OperatorRole.REVIEW))),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(authedGet("/api/ops/visit-review-disputes", null)),
        ErrorCode.UNAUTHENTICATED);
    assertFieldError(
        mockMvc.perform(jsonPostAs(decisionUrl, "{\"decision\":\"UPHELD\"}", support)),
        "decisionNote",
        "REQUIRED");

    mockMvc
        .perform(
            jsonPostAs(
                decisionUrl, "{\"decision\":\"UPHELD\",\"decisionNote\":\"기록이 맞다\"}", support))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewId").value(reviewId))
        .andExpect(jsonPath("$.reviewStatus").value("ACTIVE"))
        .andExpect(jsonPath("$.disputeStatus").value("UPHELD"))
        .andExpect(jsonPath("$.removedAt").value(nullValue()));
    assertError(
        mockMvc.perform(
            jsonPostAs(decisionUrl, "{\"decision\":\"REMOVED\",\"decisionNote\":\"다시\"}", support)),
        ErrorCode.INVALID_TRANSITION);
    assertThat(
            count(
                "SELECT count(*) FROM ops_audit_log WHERE action_type ="
                    + " 'VISIT_REVIEW_DISPUTE_DECIDED' AND target_id = ? AND detail NOT LIKE"
                    + " '%확인 연락%'",
                reviewId))
        .isEqualTo(1);

    trust(v.cookie()).andExpect(jsonPath("$.evaluatedVisitCount").value(1));
    received(v.cookie(), "")
        .andExpect(jsonPath("$.items[0].dispute.status").value("UPHELD"))
        .andExpect(jsonPath("$.items[0].dispute.decisionNote").value("기록이 맞다"))
        .andExpect(jsonPath("$.items[0].canDispute").value(false));
    reviewList(v.token()).andExpect(jsonPath("$.items[0].reviewStatus").value("LOCKED"));
    assertError(
        putReview(v.token(), v.itemId(), reviewBody("YES", "YES", "YES"), 3),
        ErrorCode.VISIT_REVIEW_LOCKED);
  }

  @Test
  void 운영자는_이의를_받아들여_삭제하거나_직권으로_삭제한다() throws Exception {
    CompletedVisit v = completedVisit();
    long reviewId = submitReview(v);
    long disputeId =
        AuthFlowSupport.json(
                fileDispute(v.cookie(), reviewId, disputeBody())
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("disputeId")
            .longValue();
    Cookie support = operatorCookie(OperatorRole.SUPPORT);
    mockMvc
        .perform(
            jsonPostAs(
                "/api/ops/visit-review-disputes/" + disputeId + "/decision",
                "{\"decision\":\"REMOVED\",\"decisionNote\":\"연락 기록이 확인됐다\"}",
                support))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewStatus").value("REMOVED"))
        .andExpect(jsonPath("$.disputeStatus").value("REMOVED"))
        .andExpect(jsonPath("$.removedAt").isNotEmpty());
    assertThat(
            count(
                "SELECT count(*) FROM visit_review WHERE id = ? AND removed_reason_code ="
                    + " 'DISPUTE_ACCEPTED'",
                reviewId))
        .isEqualTo(1);
    String removeUrl = "/api/ops/visit-reviews/" + reviewId + "/remove";
    String removeBody = "{\"reasonCode\":\"LEGAL_REQUEST\",\"decisionNote\":\"법적 요청\"}";
    assertError(
        mockMvc.perform(jsonPostAs(removeUrl, removeBody, support)), ErrorCode.INVALID_TRANSITION);

    CompletedVisit other = completedVisit();
    long otherReview = submitReview(other);
    String otherUrl = "/api/ops/visit-reviews/" + otherReview + "/remove";
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                otherUrl, "{\"reasonCode\":\"DISPUTE_ACCEPTED\",\"decisionNote\":\"x\"}", support)),
        "reasonCode",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(jsonPostAs("/api/ops/visit-reviews/987654/remove", removeBody, support)),
        ErrorCode.RESOURCE_NOT_FOUND);
    mockMvc
        .perform(jsonPostAs(otherUrl, removeBody, support))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewStatus").value("REMOVED"))
        .andExpect(jsonPath("$.disputeId").value(nullValue()))
        .andExpect(jsonPath("$.disputeStatus").value(nullValue()));
    assertThat(
            count(
                "SELECT count(*) FROM ops_audit_log WHERE action_type = 'VISIT_REVIEW_REMOVED' AND"
                    + " target_id = ? AND detail = 'reasonCode=LEGAL_REQUEST'",
                otherReview))
        .isEqualTo(1);
    reviewList(other.token()).andExpect(jsonPath("$.items[0].reviewStatus").value("LOCKED"));
    received(other.cookie(), "?status=REMOVED").andExpect(jsonPath("$.totalElements").value(1));
    trust(other.cookie()).andExpect(jsonPath("$.evaluatedVisitCount").value(0));

    CompletedVisit third = completedVisit();
    long thirdReview = submitReview(third);
    long thirdDispute =
        AuthFlowSupport.json(
                fileDispute(third.cookie(), thirdReview, disputeBody())
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("disputeId")
            .longValue();
    mockMvc
        .perform(
            jsonPostAs(
                "/api/ops/visit-reviews/" + thirdReview + "/remove",
                "{\"reasonCode\":\"PRIVACY_REQUEST\",\"decisionNote\":\"개인정보 요청\"}",
                support))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.disputeId").value(thirdDispute))
        .andExpect(jsonPath("$.disputeStatus").value("REMOVED"));
    assertThat(
            count(
                "SELECT count(*) FROM visit_review_dispute WHERE id = ? AND status = 'REMOVED'",
                thirdDispute))
        .isEqualTo(1);
  }
}
