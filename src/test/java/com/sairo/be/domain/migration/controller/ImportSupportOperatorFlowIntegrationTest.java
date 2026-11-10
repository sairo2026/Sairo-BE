package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.migration.service.ImportSupportOperatorService.Transition;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.SupportIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportSupportOperatorFlowIntegrationTest extends SupportIntegrationTestBase {

  private static ErrorCode thrown(Runnable action) {
    try {
      action.run();
    } catch (BusinessException e) {
      return e.getErrorCode();
    }
    return null;
  }

  @Test
  void 신청부터_납품까지_전체_흐름이_대표와_이전담당_사이에서_이어진다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long operator = insertOperator("이전담당");
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,", "C-2,김철수,,,", "C-3,박영희,,,");
    long requestId = toReview(cookie, batchId, operator);

    approve(cookie, requestId);
    useUpMonthlyQuota(owner, 5);
    operatorService.execute(requestId, operator);

    assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
    assertThat(count("customer")).isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT DISTINCT managing_membership_id FROM customer", Long.class))
        .isEqualTo(owner.membership().getId());
    JsonNode executed = supportDetail(cookie, requestId);
    assertThat(executed.get("status").asString()).isEqualTo("APPROVED");
    assertThat(executed.get("batchStatus").asString()).isEqualTo("COMPLETED");
    assertThat(executed.get("executionResult").get("succeeded").intValue()).isEqualTo(3);
    assertThat(executed.get("executionResult").get("failed").intValue()).isZero();
    assertThat(executed.get("executionResult").get("unprocessed").intValue()).isZero();
    assertThat(executed.get("previewSummary").isNull()).isTrue();

    Transition delivered = operatorService.deliver(requestId, operator);
    assertThat(delivered.status().name()).isEqualTo("DELIVERED");
    JsonNode detail = supportDetail(cookie, requestId);
    assertThat(detail.get("status").asString()).isEqualTo("DELIVERED");
    assertThat(detail.get("deliveredAt").isNull()).isFalse();
    assertThat(detail.get("allowedActions")).isEmpty();
  }

  @Test
  void 지원_실행은_승인한_매핑_버전으로만_한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long operator = insertOperator("이전담당");
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,");
    long requestId = toReview(cookie, batchId, operator);
    approve(cookie, requestId);
    Long approved =
        jdbcTemplate.queryForObject(
            "SELECT approved_mapping_version_id FROM import_support_request WHERE id = ?",
            Long.class,
            requestId);

    operatorService.execute(requestId, operator);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM import_mapping_version WHERE id = ?",
                Integer.class,
                approved))
        .isEqualTo(1);
    assertThat(count("import_record_link")).isEqualTo(1);
  }

  @Test
  void 담당하지_않은_운영자와_맞지_않는_상태에서는_이전담당_처리가_거절된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long operator = insertOperator("이전담당");
    long other = insertOperator("다른담당");
    long processing = toProcessing(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"), operator);

    assertThat(thrown(() -> submitMapping(processing, other, CUSTOMER_MAPPING)))
        .isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(thrown(() -> operatorService.submitReview(processing, other)))
        .isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(thrown(() -> operatorService.submitReview(processing, operator)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(thrown(() -> operatorService.execute(processing, operator)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(thrown(() -> operatorService.deliver(processing, operator)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(thrown(() -> operatorService.start(processing, operator)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 승인_뒤에는_매핑을_바꿀_수_없고_실행은_한_번만_된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long operator = insertOperator("이전담당");
    long requestId = toReview(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"), operator);
    approve(cookie, requestId);

    assertThat(thrown(() -> submitMapping(requestId, operator, CUSTOMER_MAPPING)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(thrown(() -> operatorService.deliver(requestId, operator)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    operatorService.execute(requestId, operator);
    assertThat(thrown(() -> operatorService.execute(requestId, operator)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(count("customer")).isEqualTo(1);
  }

  @Test
  void 지원_실행은_대표의_월_한도를_쓰지_않고_직접_실행은_계속_막힌다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long operator = insertOperator("이전담당");
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,");
    long requestId = toReview(cookie, batchId, operator);
    approve(cookie, requestId);
    operatorService.execute(requestId, operator);

    JsonNode list =
        AuthFlowSupport.json(mockMvc.perform(authedGet("/api/import-batches", cookie)).andReturn());
    assertThat(list.get("remainingThisMonth").intValue()).isEqualTo(5);
    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + batchId + "/execute", "", cookie)),
        ErrorCode.IMPORT_MANAGED_BY_SUPPORT);
  }

  @Test
  void 견적_금액과_거절_사유가_잘못되면_이전담당_처리가_거절된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long operator = insertOperator("이전담당");

    assertThat(thrown(() -> operatorService.quote(requestId, 0, null)))
        .isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(thrown(() -> operatorService.reject(requestId, operator, " ")))
        .isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(thrown(() -> operatorService.quote(999_999L, 220_000, null)))
        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    operatorService.quote(requestId, 330_000, "범위 조정");
    operatorService.quote(requestId, 250_000, "다시 조정");
    assertThat(supportStatus(requestId)).isEqualTo("QUOTED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT quote_amount FROM import_support_request WHERE id = ?",
                Long.class,
                requestId))
        .isEqualTo(250_000L);
    assertThat(thrown(() -> operatorService.start(requestId, operator)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
  }
}
