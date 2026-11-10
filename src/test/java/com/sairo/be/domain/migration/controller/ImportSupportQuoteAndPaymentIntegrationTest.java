package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.SupportIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportSupportQuoteAndPaymentIntegrationTest extends SupportIntegrationTestBase {

  private ResultActions accept(Cookie cookie, Object requestId) throws Exception {
    return mockMvc.perform(
        jsonPostAs("/api/import-support-requests/" + requestId + "/accept-quote", "", cookie));
  }

  private ResultActions confirm(Cookie cookie, Object requestId, String body) throws Exception {
    return mockMvc.perform(
        jsonPostAs("/api/import-support-requests/" + requestId + "/payment/confirm", body, cookie));
  }

  private static String confirmBody(JsonNode order) {
    return "{\"paymentKey\":\"toss_key_1\",\"orderId\":\""
        + order.get("paymentOrderId").asString()
        + "\",\"amount\":"
        + order.get("paymentAmount").longValue()
        + "}";
  }

  @Test
  void 견적을_수락하면_결제_주문_ID와_금액을_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toQuoted(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));

    accept(cookie, requestId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").value(requestId))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.sourceSystem").value("양셀"))
        .andExpect(jsonPath("$.estimatedRowCount").value(3))
        .andExpect(jsonPath("$.quoteAmount").value(220000))
        .andExpect(jsonPath("$.paymentAmount").value(220000))
        .andExpect(jsonPath("$.paymentOrderId").value("IMPORT-" + requestId + "-1"))
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.requestedAt").exists())
        .andExpect(jsonPath("$.deliveredAt").doesNotExist());
    assertThat(supportStatus(requestId)).isEqualTo("ACCEPTED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT payment_order_id FROM import_support_request WHERE id = ?",
                String.class,
                requestId))
        .matches("^[A-Za-z0-9_-]{6,64}$");
  }

  @Test
  void 견적_수락을_다시_호출해도_같은_주문을_돌려주는_멱등이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toQuoted(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    JsonNode first = acceptQuote(cookie, requestId);
    long versionAfterFirst = supportVersion(requestId);

    JsonNode second = acceptQuote(cookie, requestId);

    assertThat(second.get("paymentOrderId").asString())
        .isEqualTo(first.get("paymentOrderId").asString());
    assertThat(second.get("status").asString()).isEqualTo("ACCEPTED");
    assertThat(supportVersion(requestId)).isEqualTo(versionAfterFirst);
  }

  @Test
  void 견적을_받기_전이나_취소한_신청의_수락은_409_INVALID_TRANSITION이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requested = supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long cancelled = toQuoted(cookie, uploadCustomers(cookie, "C-2,김철수,,,"));
    mockMvc
        .perform(
            postWithVersion(
                "/api/import-support-requests/" + cancelled + "/cancel",
                "",
                cookie,
                supportVersion(cancelled)))
        .andExpect(status().isOk());
    long paid = toPaid(cookie, uploadCustomers(cookie, "C-3,박영희,,,"));

    assertError(accept(cookie, requested), ErrorCode.INVALID_TRANSITION);
    assertError(accept(cookie, cancelled), ErrorCode.INVALID_TRANSITION);
    assertError(accept(cookie, paid), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 견적_수락은_없는_신청과_다른_사무소와_잘못된_ID와_권한을_거른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toQuoted(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));

    assertError(accept(cookieOf(other), requestId), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(accept(cookie, 999_999), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(accept(cookie, "abc"), ErrorCode.INVALID_REQUEST);
    assertError(accept(null, requestId), ErrorCode.UNAUTHENTICATED);
    assertError(accept(cookieOf(agentOf(owner)), requestId), ErrorCode.FORBIDDEN);
    assertThat(supportStatus(requestId)).isEqualTo("QUOTED");
  }

  @Test
  void 결제를_확정하면_PAID로_바뀌고_PG_결제_키가_남는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toQuoted(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    JsonNode order = acceptQuote(cookie, requestId);
    long versionBefore = supportVersion(requestId);

    confirm(cookie, requestId, confirmBody(order))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").value(requestId))
        .andExpect(jsonPath("$.status").value("PAID"))
        .andExpect(jsonPath("$.paidAt").exists())
        .andExpect(jsonPath("$.updatedAt").exists())
        .andExpect(jsonPath("$.version").value(versionBefore + 1));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT pg_transaction_id FROM import_support_request WHERE id = ?",
                String.class,
                requestId))
        .isEqualTo("toss_key_1");
    assertThat(paymentGateway.confirms()).hasSize(1);
    assertThat(paymentGateway.confirms().get(0).amount()).isEqualTo(220000L);
  }

  @Test
  void 결제_확정은_견적_수락_때_정한_주문_ID와_금액을_대조한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toQuoted(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    acceptQuote(cookie, requestId);

    assertFieldError(
        confirm(
            cookie,
            requestId,
            "{\"paymentKey\":\"k\",\"orderId\":\"OTHER-ORDER-1\",\"amount\":220000}"),
        "orderId",
        "INVALID_VALUE");
    assertFieldError(
        confirm(
            cookie,
            requestId,
            "{\"paymentKey\":\"k\",\"orderId\":\"IMPORT-" + requestId + "-1\",\"amount\":1000}"),
        "amount",
        "INVALID_VALUE");
    assertThat(paymentGateway.confirms()).isEmpty();
    assertThat(supportStatus(requestId)).isEqualTo("ACCEPTED");
  }

  @Test
  void 결제_확정_요청의_필수_누락과_형식_오류는_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toAccepted(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));

    assertFieldError(
        confirm(cookie, requestId, "{\"orderId\":\"IMPORT-1-1\",\"amount\":220000}"),
        "paymentKey",
        "REQUIRED");
    assertFieldError(
        confirm(cookie, requestId, "{\"paymentKey\":\"k\",\"amount\":220000}"),
        "orderId",
        "REQUIRED");
    assertFieldError(
        confirm(cookie, requestId, "{\"paymentKey\":\"k\",\"orderId\":\"IMPORT-1-1\"}"),
        "amount",
        "REQUIRED");
    assertFieldError(
        confirm(cookie, requestId, "{\"paymentKey\":\"k\",\"orderId\":\"a\",\"amount\":220000}"),
        "orderId",
        "INVALID_FORMAT");
    assertFieldError(
        confirm(
            cookie,
            requestId,
            "{\"paymentKey\":\"k\",\"orderId\":\"IMPORT-1-1\",\"amount\":220000,\"extra\":1}"),
        "extra",
        "NOT_ALLOWED");
  }

  @Test
  void ACCEPTED가_아닌_신청의_결제_확정은_409_INVALID_TRANSITION이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long quoted = toQuoted(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long paid = toPaid(cookie, uploadCustomers(cookie, "C-2,김철수,,,"));
    String body = "{\"paymentKey\":\"k\",\"orderId\":\"IMPORT-1-1\",\"amount\":220000}";

    assertError(confirm(cookie, quoted, body), ErrorCode.INVALID_TRANSITION);
    assertError(confirm(cookie, paid, body), ErrorCode.INVALID_TRANSITION);
    assertThat(paymentGateway.confirms()).hasSize(1);
  }

  @Test
  void PG가_승인을_거절하면_402_PAYMENT_FAILED이고_상태는_그대로다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toQuoted(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    JsonNode order = acceptQuote(cookie, requestId);
    long versionBefore = supportVersion(requestId);
    paymentGateway.respondWith(PaymentStatus.FAILED);

    assertError(confirm(cookie, requestId, confirmBody(order)), ErrorCode.PAYMENT_FAILED);

    assertThat(supportStatus(requestId)).isEqualTo("ACCEPTED");
    assertThat(supportVersion(requestId)).isEqualTo(versionBefore);
    paymentGateway.respondWith(PaymentStatus.SUCCEEDED);
    confirm(cookie, requestId, confirmBody(order)).andExpect(status().isOk());
  }

  @Test
  void 결제_결과가_확인_중이면_409_PAYMENT_VERIFYING이고_다시_호출해_확정할_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toQuoted(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    JsonNode order = acceptQuote(cookie, requestId);
    paymentGateway.respondWith(PaymentStatus.VERIFYING);

    assertError(confirm(cookie, requestId, confirmBody(order)), ErrorCode.PAYMENT_VERIFYING);
    assertThat(supportStatus(requestId)).isEqualTo("ACCEPTED");

    paymentGateway.throwOnNextCharge(new IllegalStateException("timeout"));
    assertError(confirm(cookie, requestId, confirmBody(order)), ErrorCode.PAYMENT_VERIFYING);
    assertThat(supportStatus(requestId)).isEqualTo("ACCEPTED");

    paymentGateway.respondWith(PaymentStatus.SUCCEEDED);
    confirm(cookie, requestId, confirmBody(order))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAID"));
  }

  @Test
  void 결제_확정은_없는_신청과_다른_사무소와_권한을_거른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long requestId = toAccepted(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    String body =
        "{\"paymentKey\":\"k\",\"orderId\":\"IMPORT-" + requestId + "-1\",\"amount\":220000}";

    assertError(confirm(cookieOf(other), requestId, body), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(confirm(cookie, 999_999, body), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(confirm(null, requestId, body), ErrorCode.UNAUTHENTICATED);
    assertError(confirm(cookieOf(agentOf(owner)), requestId, body), ErrorCode.FORBIDDEN);
    assertThat(paymentGateway.confirms()).isEmpty();
  }
}
