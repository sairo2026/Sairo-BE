package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitReviewTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ApproverVisitReviewIntegrationTest extends VisitReviewTestBase {

  @Test
  void 승인자는_완료_방문을_평가하고_기한_안에서_수정_철회_재입력한다() throws Exception {
    CompletedVisit v = completedVisit();

    reviewList(v.token())
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.reviewWindowDays").value(14))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].scheduleItemId").value(v.itemId()))
        .andExpect(jsonPath("$.items[0].reviewStatus").value("NONE"))
        .andExpect(jsonPath("$.items[0].editable").value(true))
        .andExpect(jsonPath("$.items[0].revisionsRemaining").value(2))
        .andExpect(jsonPath("$.items[0].version").value(nullValue()))
        .andExpect(jsonPath("$.items[0].answers").value(nullValue()))
        .andExpect(jsonPath("$.items[0].isPreferredBroker").value(false))
        .andExpect(jsonPath("$.items[0].applicantBrokerName").isNotEmpty())
        .andExpect(jsonPath("$.privacyNotice.noticeCode").value("GUEST_LINK_NOTICE_APPROVER"));

    putReview(v.token(), v.itemId(), reviewBody("YES", "NO", "SKIP"), null)
        .andExpect(status().isCreated())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.reviewStatus").value("SUBMITTED"))
        .andExpect(jsonPath("$.answers.punctuality").value("YES"))
        .andExpect(jsonPath("$.answers.advanceContact").value("NO"))
        .andExpect(jsonPath("$.answers.asRequested").value("SKIP"))
        .andExpect(jsonPath("$.revisionCount").value(0))
        .andExpect(jsonPath("$.revisionsRemaining").value(2))
        .andExpect(jsonPath("$.version").value(1));
    assertThat(
            count(
                "SELECT count(*) FROM visit_review WHERE schedule_item_id = ? AND"
                    + " reviewed_membership_id = ? AND status = 'ACTIVE'",
                v.itemId(),
                v.s().base().owner().membership().getId()))
        .isEqualTo(1);

    assertFieldError(
        putReview(v.token(), v.itemId(), reviewBody("YES", "YES", "YES"), null),
        "If-Match",
        "REQUIRED");
    assertError(
        putReview(v.token(), v.itemId(), reviewBody("YES", "YES", "YES"), 9),
        ErrorCode.VERSION_CONFLICT);
    putReview(v.token(), v.itemId(), reviewBody("YES", "YES", "YES"), 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revisionCount").value(1))
        .andExpect(jsonPath("$.version").value(2));

    withdrawReview(v.token(), v.itemId(), 2)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewStatus").value("WITHDRAWN"))
        .andExpect(jsonPath("$.answers").value(nullValue()))
        .andExpect(jsonPath("$.revisionCount").value(1));
    assertError(withdrawReview(v.token(), v.itemId(), 3), ErrorCode.RESOURCE_NOT_FOUND);
    reviewList(v.token())
        .andExpect(jsonPath("$.items[0].reviewStatus").value("WITHDRAWN"))
        .andExpect(jsonPath("$.items[0].answers").value(nullValue()))
        .andExpect(jsonPath("$.items[0].version").value(3));

    putReview(v.token(), v.itemId(), reviewBody("NO", "NO", "NO"), 3)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewStatus").value("SUBMITTED"))
        .andExpect(jsonPath("$.revisionCount").value(2))
        .andExpect(jsonPath("$.revisionsRemaining").value(0));
    reviewList(v.token())
        .andExpect(jsonPath("$.items[0].reviewStatus").value("SUBMITTED"))
        .andExpect(jsonPath("$.items[0].editable").value(false));
    assertError(
        putReview(v.token(), v.itemId(), reviewBody("YES", "NO", "NO"), 4),
        ErrorCode.VISIT_REVIEW_LOCKED);
  }

  @Test
  void 평가_입력은_형식과_대상과_기한을_검사한다() throws Exception {
    CompletedVisit v = completedVisit();

    ResultActions allSkip =
        putReview(v.token(), v.itemId(), reviewBody("SKIP", "SKIP", "SKIP"), null);
    assertFieldError(allSkip, "punctuality", "INVALID_VALUE");
    allSkip
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'advanceContact')]").exists())
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'asRequested')]").exists());
    assertFieldError(
        putReview(v.token(), v.itemId(), reviewBody("MAYBE", "YES", "YES"), null),
        "punctuality",
        "NOT_ALLOWED");
    assertFieldError(
        putReview(
            v.token(),
            v.itemId(),
            "{\"punctuality\":\"YES\",\"advanceContact\":\"YES\",\"asRequested\":\"YES\","
                + "\"acknowledgedNoticeVersion\":\""
                + NOTICE_VERSION
                + "\",\"score\":5}",
            null),
        "score",
        "NOT_ALLOWED");
    assertError(
        putReview(v.token(), v.itemId(), reviewBody("YES", "YES", "YES", "0.9"), null),
        ErrorCode.NOTICE_VERSION_CHANGED);
    assertError(
        putReview(v.token(), 987654L, reviewBody("YES", "YES", "YES"), null),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        putReview("no-such-token", v.itemId(), reviewBody("YES", "YES", "YES"), null),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(reviewList("no-such-token"), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertThat(count("SELECT count(*) FROM visit_review")).isZero();

    jdbcTemplate.update(
        "UPDATE visit_schedule_item SET result_recorded_at = now() - interval '15 days' WHERE"
            + " id = ?",
        v.itemId());
    assertError(
        putReview(v.token(), v.itemId(), reviewBody("YES", "YES", "YES"), null),
        ErrorCode.VISIT_REVIEW_WINDOW_CLOSED);
    reviewList(v.token()).andExpect(jsonPath("$.items.length()").value(0));

    SelectionFixture scheduled = confirmed();
    long pending = scheduledItem(scheduled.coordinationId());
    assertError(
        putReview(scheduled.token(), pending, reviewBody("YES", "YES", "YES"), null),
        ErrorCode.VISIT_REVIEW_WINDOW_CLOSED);
    assertError(
        putReview(scheduled.token(), v.itemId(), reviewBody("YES", "YES", "YES"), null),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 같은_멱등키의_재시도는_같은_평가를_돌려주고_철회는_If_Match가_필요하다() throws Exception {
    CompletedVisit v = completedVisit();
    putReview(v.token(), v.itemId(), reviewBody("YES", "YES", "NO"), null, "key-1")
        .andExpect(status().isCreated());
    putReview(v.token(), v.itemId(), reviewBody("YES", "YES", "NO"), null, "key-1")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(1));
    putReview(v.token(), v.itemId(), reviewBody("NO", "YES", "NO"), 1, "key-2")
        .andExpect(status().isOk());
    putReview(v.token(), v.itemId(), reviewBody("NO", "YES", "NO"), 1, "key-2")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revisionCount").value(1))
        .andExpect(jsonPath("$.version").value(2));

    assertFieldError(withdrawReview(v.token(), v.itemId(), null), "If-Match", "REQUIRED");
    assertError(withdrawReview(v.token(), v.itemId(), 1), ErrorCode.VERSION_CONFLICT);
    jdbcTemplate.update(
        "UPDATE visit_review SET review_deadline_at = now() - interval '1 minute',"
            + " submitted_at = now() - interval '2 days' WHERE schedule_item_id = ?",
        v.itemId());
    assertError(withdrawReview(v.token(), v.itemId(), 2), ErrorCode.VISIT_REVIEW_WINDOW_CLOSED);
    reviewList(v.token())
        .andExpect(jsonPath("$.items[0].reviewStatus").value("SUBMITTED"))
        .andExpect(jsonPath("$.items[0].editable").value(false));
  }

  @Test
  void 공동_승인자는_평가와_선호_목록을_읽기만_한다() throws Exception {
    CompletedVisit v = completedVisit();
    submitReview(v);
    long coApproval =
        registerApproval(v.cookie(), v.s().base().propertyId(), "CO_APPROVER", -1, 30);
    String coToken = issueApproverToken(v.cookie(), v.s().base().propertyId(), coApproval);

    reviewList(coToken)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].reviewStatus").value("SUBMITTED"))
        .andExpect(jsonPath("$.items[0].editable").value(false));
    assertError(
        putReview(coToken, v.itemId(), reviewBody("YES", "YES", "YES"), 1),
        ErrorCode.APPROVER_READ_ONLY);
    assertError(withdrawReview(coToken, v.itemId(), 1), ErrorCode.APPROVER_READ_ONLY);
    mockMvc
        .perform(get(linkUrl(coToken) + "/preferred-brokers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidates.length()").value(1));
    assertError(
        mockMvc.perform(
            jsonPostAs(
                linkUrl(coToken) + "/preferred-brokers",
                "{\"sourceRequestId\":"
                    + v.s().requestId()
                    + ",\"acknowledgedNoticeVersion\":\""
                    + NOTICE_VERSION
                    + "\"}",
                null)),
        ErrorCode.APPROVER_READ_ONLY);
  }

  @Test
  void 승인자는_선호_중개사를_다섯_명까지_지정하고_해제한다() throws Exception {
    SelectionFixture s = selectionFixture();
    String url = linkUrl(s.token()) + "/preferred-brokers";

    mockMvc
        .perform(get(url))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.maxActiveCount").value(5))
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.candidates.length()").value(1))
        .andExpect(jsonPath("$.candidates[0].sourceRequestId").value(s.requestId()))
        .andExpect(jsonPath("$.candidates[0].sourceScheduleItemId").value(nullValue()))
        .andExpect(jsonPath("$.candidates[0].applicantBrokerName").isNotEmpty())
        .andExpect(jsonPath("$.privacyNotice.noticeVersion").value(NOTICE_VERSION));
    compare(s.token()).andExpect(jsonPath("$.items[0].isPreferredBroker").value(false));

    assertFieldError(
        mockMvc.perform(jsonPostAs(url, preferBody(null, null), null)),
        "sourceRequestId",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, preferBody(s.requestId(), 5L), null)),
        "sourceRequestId",
        "INVALID_VALUE");
    assertError(
        mockMvc.perform(jsonPostAs(url, preferBody(987654L, null), null)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                url,
                "{\"sourceRequestId\":" + s.requestId() + ",\"acknowledgedNoticeVersion\":\"0.9\"}",
                null)),
        ErrorCode.NOTICE_VERSION_CHANGED);

    long preferredId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(jsonPostAs(url, preferBody(s.requestId(), null), null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.applicantOfficeName").isNotEmpty())
                    .andExpect(jsonPath("$.releasedAt").value(nullValue()))
                    .andReturn())
            .get("preferredBrokerId")
            .longValue();
    assertError(
        mockMvc.perform(jsonPostAs(url, preferBody(s.requestId(), null), null)),
        ErrorCode.PREFERRED_BROKER_DUPLICATE);
    mockMvc
        .perform(get(url))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].preferredBrokerId").value(preferredId))
        .andExpect(jsonPath("$.candidates.length()").value(0));
    compare(s.token()).andExpect(jsonPath("$.items[0].isPreferredBroker").value(true));
    assertThat(
            count(
                "SELECT count(*) FROM visit_preferred_broker WHERE id = ? AND"
                    + " designated_via_link_id IS NOT NULL",
                preferredId))
        .isEqualTo(1);

    mockMvc
        .perform(jsonPostAs(url + "/" + preferredId + "/release", "", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.releasedAt").isNotEmpty());
    assertError(
        mockMvc.perform(jsonPostAs(url + "/" + preferredId + "/release", "", null)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(jsonPostAs(url + "/987654/release", "", null)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(count("SELECT count(*) FROM visit_preferred_broker")).isEqualTo(1);

    long linkId =
        count(
            "SELECT id FROM visit_approver_property_link WHERE approval_id = ? AND revoked_at IS"
                + " NULL",
            s.base().approvalId());
    for (int i = 0; i < 5; i++) {
      long member = newStaff(OfficeMembershipRole.OWNER).membership().getId();
      jdbcTemplate.update(
          "INSERT INTO visit_preferred_broker (approval_id, broker_membership_id,"
              + " designated_via_link_id, source_request_id) VALUES (?, ?, ?, ?)",
          s.base().approvalId(),
          member,
          linkId,
          s.requestId());
    }
    assertError(
        mockMvc.perform(jsonPostAs(url, preferBody(s.requestId(), null), null)),
        ErrorCode.PREFERRED_BROKER_LIMIT_EXCEEDED);
  }

  @Test
  void 평가_대기_방문이_있으면_승인자_링크_만료를_평가_기한까지_늘린다() throws Exception {
    CompletedVisit v = completedVisit();
    long approvalId = v.s().base().approvalId();
    long propertyId = v.s().base().propertyId();
    jdbcTemplate.update(
        "UPDATE visit_schedule_item SET result_recorded_at = now() - interval '11 days' WHERE"
            + " id = ?",
        v.itemId());
    jdbcTemplate.update(
        "UPDATE property_access_approval SET valid_until = now() + interval '1 day' WHERE id ="
            + " ?",
        approvalId);
    issueApproverToken(v.cookie(), propertyId, approvalId);
    Instant expires = activeLinkExpiry(approvalId);
    assertThat(expires).isAfter(Instant.now().plus(Duration.ofDays(2)));
    assertThat(expires).isBefore(Instant.now().plus(Duration.ofDays(4)));

    jdbcTemplate.update(
        "UPDATE property_access_approval SET valid_until = now() - interval '1 minute' WHERE id"
            + " = ?",
        approvalId);
    String token = issueApproverToken(v.cookie(), propertyId, approvalId);
    putReview(token, v.itemId(), reviewBody("YES", "YES", "YES"), null)
        .andExpect(status().isCreated());

    jdbcTemplate.update(
        "UPDATE visit_schedule_item SET result_recorded_at = now() - interval '20 days' WHERE"
            + " id = ?",
        v.itemId());
    jdbcTemplate.update(
        "UPDATE visit_review SET submitted_at = now() - interval '19 days', last_revised_at ="
            + " now() - interval '19 days', review_deadline_at = now() - interval '6 days'"
            + " WHERE schedule_item_id = ?",
        v.itemId());
    assertError(
        mockMvc.perform(
            jsonPostAs(
                approvalsUrl(propertyId) + "/" + approvalId + "/approver-property-link",
                "",
                v.cookie())),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 평가_응답에는_점수_별점_순위_필드가_없다() throws Exception {
    CompletedVisit v = completedVisit();
    assertNoScoreFields(
        AuthFlowSupport.json(
            putReview(v.token(), v.itemId(), reviewBody("YES", "YES", "NO"), null)
                .andExpect(status().isCreated())
                .andReturn()));
    assertNoScoreFields(AuthFlowSupport.json(reviewList(v.token()).andReturn()));
    assertNoScoreFields(
        AuthFlowSupport.json(
            mockMvc.perform(get(linkUrl(v.token()) + "/preferred-brokers")).andReturn()));
    SelectionFixture s = selectionFixture();
    assertNoScoreFields(
        AuthFlowSupport.json(compare(s.token()).andExpect(status().isOk()).andReturn()));
    assertNoScoreFields(
        AuthFlowSupport.json(
            mockMvc
                .perform(authedGet("/api/visit-trust/me", v.cookie()))
                .andExpect(status().isOk())
                .andReturn()));
    assertNoScoreFields(
        AuthFlowSupport.json(
            mockMvc
                .perform(authedGet("/api/visit-reviews/received", v.cookie()))
                .andExpect(status().isOk())
                .andReturn()));
  }

  private Instant activeLinkExpiry(long approvalId) {
    Timestamp value =
        jdbcTemplate.queryForObject(
            "SELECT expires_at FROM visit_approver_property_link WHERE approval_id = ? AND"
                + " revoked_at IS NULL",
            Timestamp.class,
            approvalId);
    return value == null ? Instant.EPOCH : value.toInstant();
  }

  private static String preferBody(Long requestId, Long itemId) {
    StringBuilder body = new StringBuilder("{");
    if (requestId != null) {
      body.append("\"sourceRequestId\":").append(requestId).append(',');
    }
    if (itemId != null) {
      body.append("\"sourceScheduleItemId\":").append(itemId).append(',');
    }
    return body.append("\"acknowledgedNoticeVersion\":\"")
        .append(NOTICE_VERSION)
        .append("\"}")
        .toString();
  }
}
