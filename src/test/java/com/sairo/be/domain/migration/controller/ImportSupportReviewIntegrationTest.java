package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.SupportIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportSupportReviewIntegrationTest extends SupportIntegrationTestBase {

  private static final String PREFIX = "/api/import-support-requests/";

  private ResultActions approveCall(Cookie cookie, Object requestId, Object version)
      throws Exception {
    return mockMvc.perform(postWithVersion(PREFIX + requestId + "/approve", null, cookie, version));
  }

  private ResultActions changesCall(Cookie cookie, Object requestId, Object version, String body)
      throws Exception {
    return mockMvc.perform(
        postWithVersion(PREFIX + requestId + "/request-changes", body, cookie, version));
  }

  private ResultActions cancelCall(Cookie cookie, Object requestId, Object version, String body)
      throws Exception {
    return mockMvc.perform(postWithVersion(PREFIX + requestId + "/cancel", body, cookie, version));
  }

  @Test
  void 검수_중인_신청의_상세는_제출한_매핑과_미리보기_요약과_가능한_처리를_보여준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long operator = insertOperator("이전담당");
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,", "C-2,김철수,,,");
    long requestId = toReview(cookie, batchId, operator);

    mockMvc
        .perform(authedGet(PREFIX + requestId, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").value(requestId))
        .andExpect(jsonPath("$.importBatchId").value(batchId))
        .andExpect(jsonPath("$.sourceSystem").value("양셀"))
        .andExpect(jsonPath("$.estimatedRowCount").value(3))
        .andExpect(jsonPath("$.quoteAmount").value(220000))
        .andExpect(jsonPath("$.status").value("REVIEW"))
        .andExpect(jsonPath("$.assignedOperatorName").value("이전담당"))
        .andExpect(jsonPath("$.processingStartedAt").exists())
        .andExpect(jsonPath("$.submittedAt").exists())
        .andExpect(jsonPath("$.changeRequestCount").value(0))
        .andExpect(jsonPath("$.approvedAt").doesNotExist())
        .andExpect(jsonPath("$.directWorkAllowed").value(false))
        .andExpect(jsonPath("$.allowedActions[0]").value("APPROVE"))
        .andExpect(jsonPath("$.allowedActions[1]").value("REQUEST_CHANGES"))
        .andExpect(jsonPath("$.batchStatus").value("MAPPING"))
        .andExpect(jsonPath("$.executionResult").doesNotExist())
        .andExpect(jsonPath("$.submittedMapping.versionNo").value(1))
        .andExpect(jsonPath("$.submittedMapping.sourceSystem").value("양셀"))
        .andExpect(jsonPath("$.submittedMapping.targetType").value("CUSTOMER"))
        .andExpect(jsonPath("$.submittedMapping.confirmedByType").value("OPERATOR"))
        .andExpect(jsonPath("$.submittedMapping.items.length()").value(5))
        .andExpect(jsonPath("$.previewSummary.rowCount").value(2))
        .andExpect(jsonPath("$.previewSummary.warningCount").value(0))
        .andExpect(jsonPath("$.previewSummary.duplicateCount").value(0))
        .andExpect(jsonPath("$.version").value(supportVersion(requestId)));
  }

  @Test
  void 견적과_결제_전후의_상세는_범위_비고와_주문_정보와_가능한_처리를_보여준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requested = supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long quoted = toQuoted(cookie, uploadCustomers(cookie, "C-2,김철수,,,"));
    long paid = toPaid(cookie, uploadCustomers(cookie, "C-3,박영희,,,"));

    mockMvc
        .perform(authedGet(PREFIX + requested, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.quoteAmount").doesNotExist())
        .andExpect(jsonPath("$.scopeNote").doesNotExist())
        .andExpect(jsonPath("$.allowedActions.length()").value(1))
        .andExpect(jsonPath("$.allowedActions[0]").value("CANCEL"))
        .andExpect(jsonPath("$.submittedMapping").doesNotExist())
        .andExpect(jsonPath("$.previewSummary").doesNotExist())
        .andExpect(jsonPath("$.directWorkAllowed").value(false));
    mockMvc
        .perform(authedGet(PREFIX + quoted, cookie))
        .andExpect(jsonPath("$.scopeNote").value("원본 1종, 최대 5만레코드"))
        .andExpect(jsonPath("$.paymentOrderId").doesNotExist())
        .andExpect(jsonPath("$.allowedActions[0]").value("CANCEL"));
    acceptQuote(cookie, quoted);
    mockMvc
        .perform(authedGet(PREFIX + quoted, cookie))
        .andExpect(jsonPath("$.paymentOrderId").value("IMPORT-" + quoted + "-1"))
        .andExpect(jsonPath("$.paymentAmount").value(220000));
    mockMvc
        .perform(authedGet(PREFIX + paid, cookie))
        .andExpect(jsonPath("$.status").value("PAID"))
        .andExpect(jsonPath("$.paidAt").exists())
        .andExpect(jsonPath("$.allowedActions.length()").value(0));
  }

  @Test
  void 상세는_없는_신청과_다른_사무소와_권한을_거른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));

    assertError(
        mockMvc.perform(authedGet(PREFIX + requestId, cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(authedGet(PREFIX + 999_999, cookie)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(authedGet(PREFIX + "abc", cookie)), ErrorCode.INVALID_REQUEST);
    assertError(mockMvc.perform(authedGet(PREFIX + requestId, null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet(PREFIX + requestId, cookieOf(agentOf(owner)))),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 승인하면_제출된_매핑_버전이_승인_버전으로_고정된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long operator = insertOperator("이전담당");
    long requestId = toReview(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"), operator);
    long versionBefore = supportVersion(requestId);

    approveCall(cookie, requestId, versionBefore)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").value(requestId))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.approvedMappingVersionNo").value(1))
        .andExpect(jsonPath("$.approvedAt").exists())
        .andExpect(jsonPath("$.version").value(versionBefore + 1));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT approved_by_membership_id FROM import_support_request WHERE id = ?",
                Long.class,
                requestId))
        .isEqualTo(owner.membership().getId());
    JsonNode detail = supportDetail(cookie, requestId);
    assertThat(detail.get("approvedMappingVersionNo").intValue()).isEqualTo(1);
    assertThat(detail.get("allowedActions")).isEmpty();
  }

  @Test
  void 승인은_검수_상태가_아니면_409_INVALID_TRANSITION이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long processing =
        toProcessing(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"), insertOperator("담당"));
    long accepted = toAccepted(cookie, uploadCustomers(cookie, "C-2,김철수,,,"));

    assertError(
        approveCall(cookie, processing, supportVersion(processing)), ErrorCode.INVALID_TRANSITION);
    assertError(
        approveCall(cookie, accepted, supportVersion(accepted)), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 승인은_If_Match가_없으면_400이고_버전이_다르면_409_VERSION_CONFLICT이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toReview(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"), insertOperator("담당"));
    long version = supportVersion(requestId);

    assertFieldError(approveCall(cookie, requestId, null), "If-Match", "REQUIRED");
    assertError(approveCall(cookie, requestId, version - 1), ErrorCode.VERSION_CONFLICT)
        .andExpect(jsonPath("$.currentVersion").value(version));
    assertThat(supportStatus(requestId)).isEqualTo("REVIEW");
  }

  @Test
  void 승인은_없는_신청과_다른_사무소와_권한을_거른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toReview(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"), insertOperator("담당"));
    long version = supportVersion(requestId);

    assertError(approveCall(cookieOf(other), requestId, version), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(approveCall(cookie, 999_999, 1), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(approveCall(null, requestId, version), ErrorCode.UNAUTHENTICATED);
    assertError(approveCall(cookieOf(agentOf(owner)), requestId, version), ErrorCode.FORBIDDEN);
  }

  @Test
  void 수정을_요청하면_처리중으로_돌아가고_두_번째_요청은_409_CHANGE_REQUEST_LIMIT_EXCEEDED이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long operator = insertOperator("이전담당");
    long requestId = toReview(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"), operator);
    long versionBefore = supportVersion(requestId);

    changesCall(cookie, requestId, versionBefore, CHANGE_BODY)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").value(requestId))
        .andExpect(jsonPath("$.status").value("PROCESSING"))
        .andExpect(jsonPath("$.changeRequestCount").value(1))
        .andExpect(jsonPath("$.changeRequestedAt").exists())
        .andExpect(jsonPath("$.version").value(versionBefore + 1));
    JsonNode detail = supportDetail(cookie, requestId);
    assertThat(detail.get("changeRequestReason").asString()).contains("전화번호");

    submitMapping(requestId, operator, CUSTOMER_MAPPING);
    operatorService.submitReview(requestId, operator);
    JsonNode again = supportDetail(cookie, requestId);
    assertThat(again.get("status").asString()).isEqualTo("REVIEW");
    assertThat(again.get("submittedMapping").get("versionNo").intValue()).isEqualTo(2);
    assertThat(again.get("allowedActions")).hasSize(1);
    assertError(
        changesCall(cookie, requestId, supportVersion(requestId), CHANGE_BODY),
        ErrorCode.CHANGE_REQUEST_LIMIT_EXCEEDED);
    assertThat(supportStatus(requestId)).isEqualTo("REVIEW");
  }

  @Test
  void 수정_요청은_사유가_없으면_400이고_검수_상태가_아니면_409이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long review = toReview(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"), insertOperator("담당"));
    long accepted = toAccepted(cookie, uploadCustomers(cookie, "C-2,김철수,,,"));

    assertFieldError(
        changesCall(cookie, review, supportVersion(review), "{}"), "reason", "REQUIRED");
    assertFieldError(
        changesCall(cookie, review, supportVersion(review), "{\"reason\":\"  \"}"),
        "reason",
        "REQUIRED");
    assertFieldError(
        changesCall(cookie, review, supportVersion(review), "{\"reason\":\"a\",\"x\":1}"),
        "x",
        "NOT_ALLOWED");
    assertError(
        changesCall(cookie, accepted, supportVersion(accepted), CHANGE_BODY),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 수정_요청은_If_Match와_없는_신청과_다른_사무소와_권한을_거른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toReview(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"), insertOperator("담당"));
    long version = supportVersion(requestId);

    assertFieldError(changesCall(cookie, requestId, null, CHANGE_BODY), "If-Match", "REQUIRED");
    assertError(
        changesCall(cookie, requestId, version + 5, CHANGE_BODY), ErrorCode.VERSION_CONFLICT);
    assertError(
        changesCall(cookieOf(other), requestId, version, CHANGE_BODY),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(changesCall(cookie, 999_999, 1, CHANGE_BODY), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(changesCall(null, requestId, version, CHANGE_BODY), ErrorCode.UNAUTHENTICATED);
    assertError(
        changesCall(cookieOf(agentOf(owner)), requestId, version, CHANGE_BODY),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 결제_전_신청은_취소할_수_있고_직접_작업_권한이_돌아온다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requested = supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long quoted = toQuoted(cookie, uploadCustomers(cookie, "C-2,김철수,,,"));
    long accepted = toAccepted(cookie, uploadCustomers(cookie, "C-3,박영희,,,"));

    for (long id : new long[] {requested, quoted, accepted}) {
      long version = supportVersion(id);
      cancelCall(cookie, id, version, "{\"reason\":\"단순 변심\"}")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.requestId").value(id))
          .andExpect(jsonPath("$.status").value("CANCELLED"))
          .andExpect(jsonPath("$.cancelledAt").exists())
          .andExpect(jsonPath("$.directWorkAllowed").value(true))
          .andExpect(jsonPath("$.importBatchId").isNumber())
          .andExpect(jsonPath("$.version").value(version + 1));
    }
    JsonNode detail = supportDetail(cookie, requested);
    assertThat(detail.get("cancelReason").asString()).isEqualTo("단순 변심");
    assertThat(detail.get("directWorkAllowed").booleanValue()).isTrue();
    assertThat(detail.get("allowedActions")).isEmpty();
  }

  @Test
  void 취소는_본문과_사유가_없어도_되고_사유_형식_오류는_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long first = supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long second = supportOn(cookie, uploadCustomers(cookie, "C-2,김철수,,,"));

    assertFieldError(
        cancelCall(cookie, first, supportVersion(first), "{\"reason\":1}"),
        "reason",
        "TYPE_MISMATCH");
    assertFieldError(
        cancelCall(cookie, first, supportVersion(first), "{\"other\":1}"), "other", "NOT_ALLOWED");
    cancelCall(cookie, first, supportVersion(first), null).andExpect(status().isOk());
    cancelCall(cookie, second, supportVersion(second), "{\"reason\":\"\"}")
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT cancel_reason FROM import_support_request WHERE id = ?",
                String.class,
                second))
        .isNull();
  }

  @Test
  void 결제_후와_이미_끝난_신청은_취소할_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long operator = insertOperator("담당");
    long paid = toPaid(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long processing = toProcessing(cookie, uploadCustomers(cookie, "C-2,김철수,,,"), operator);
    long review = toReview(cookie, uploadCustomers(cookie, "C-3,박영희,,,"), operator);
    long cancelled = supportOn(cookie, uploadCustomers(cookie, "C-4,최민수,,,"));
    cancelCall(cookie, cancelled, supportVersion(cancelled), null).andExpect(status().isOk());

    for (long id : new long[] {paid, processing, review, cancelled}) {
      assertError(cancelCall(cookie, id, supportVersion(id), null), ErrorCode.INVALID_TRANSITION);
    }
    assertThat(supportStatus(paid)).isEqualTo("PAID");
  }

  @Test
  void 취소는_If_Match와_없는_신청과_다른_사무소와_권한을_거른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long version = supportVersion(requestId);

    assertFieldError(cancelCall(cookie, requestId, null, null), "If-Match", "REQUIRED");
    assertError(cancelCall(cookie, requestId, version + 1, null), ErrorCode.VERSION_CONFLICT);
    assertError(
        cancelCall(cookieOf(other), requestId, version, null), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(cancelCall(cookie, 999_999, 1, null), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(cancelCall(null, requestId, version, null), ErrorCode.UNAUTHENTICATED);
    assertError(
        cancelCall(cookieOf(agentOf(owner)), requestId, version, null), ErrorCode.FORBIDDEN);
    assertThat(supportStatus(requestId)).isEqualTo("REQUESTED");
  }

  @Test
  void 거절된_신청은_직접_작업_권한이_돌아오고_거절_사유가_상세에_나온다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,");
    long requestId = toQuoted(cookie, batchId);
    long operator = insertOperator("담당");

    operatorService.reject(requestId, operator, "범위를 지원하지 않습니다");

    JsonNode detail = supportDetail(cookie, requestId);
    assertThat(detail.get("status").asString()).isEqualTo("REJECTED");
    assertThat(detail.get("rejectReason").asString()).contains("범위");
    assertThat(detail.get("directWorkAllowed").booleanValue()).isTrue();
    confirmMapping(cookie, batchId, CUSTOMER_MAPPING);
  }
}
