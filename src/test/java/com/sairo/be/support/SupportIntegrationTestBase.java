package com.sairo.be.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.migration.dto.request.ImportMappingConfirmRequest;
import com.sairo.be.domain.migration.service.ImportRequestParser;
import com.sairo.be.domain.migration.service.ImportSupportOperatorService;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public abstract class SupportIntegrationTestBase extends ImportIntegrationTestBase {

  protected static final String CHANGE_BODY = "{\"reason\":\"연락처 열을 전화번호로 매핑해 주세요\"}";

  @Autowired protected ImportSupportOperatorService operatorService;
  @Autowired protected StubPaymentGateway paymentGateway;

  @BeforeEach
  void resetPaymentGateway() {
    paymentGateway.reset();
  }

  protected static MockHttpServletRequestBuilder postWithVersion(
      String url, String body, Cookie cookie, Object version) {
    MockHttpServletRequestBuilder builder = post(url).contentType(MediaType.APPLICATION_JSON);
    if (body != null) {
      builder.content(body);
    }
    if (version != null) {
      builder.header("If-Match", String.valueOf(version));
    }
    return withSession(builder, cookie);
  }

  protected long insertOperator(String name) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
                + " totp_secret_encrypted) VALUES (?, ?, 'ACTIVE', true, 'x') RETURNING id",
            Long.class,
            "kakao-" + name + "-" + System.nanoTime(),
            name);
    return id == null ? -1 : id;
  }

  protected TestData.Staff agentOf(TestData.Staff owner) {
    return staffIn(owner.office(), OfficeMembershipRole.AGENT);
  }

  protected JsonNode createSupport(Cookie cookie, long batchId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                jsonPostAs(
                    "/api/import-support-requests",
                    "{\"importBatchId\":"
                        + batchId
                        + ",\"sourceSystem\":\"양셀\",\"estimatedRowCount\":3}",
                    cookie))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result);
  }

  protected long supportOn(Cookie cookie, long batchId) throws Exception {
    return createSupport(cookie, batchId).get("requestId").longValue();
  }

  protected JsonNode acceptQuote(Cookie cookie, long requestId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                jsonPostAs(
                    "/api/import-support-requests/" + requestId + "/accept-quote", "", cookie))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result);
  }

  protected JsonNode confirmPayment(Cookie cookie, long requestId, JsonNode order)
      throws Exception {
    String body =
        "{\"paymentKey\":\"toss_key_1\",\"orderId\":\""
            + order.get("paymentOrderId").asString()
            + "\",\"amount\":"
            + order.get("paymentAmount").longValue()
            + "}";
    MvcResult result =
        mockMvc
            .perform(
                jsonPostAs(
                    "/api/import-support-requests/" + requestId + "/payment/confirm", body, cookie))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result);
  }

  protected long supportVersion(long requestId) {
    Long version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM import_support_request WHERE id = ?", Long.class, requestId);
    return version == null ? -1 : version;
  }

  protected String supportStatus(long requestId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM import_support_request WHERE id = ?", String.class, requestId);
  }

  protected JsonNode supportDetail(Cookie cookie, long requestId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(authedGet("/api/import-support-requests/" + requestId, cookie))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result);
  }

  protected long toQuoted(Cookie cookie, long batchId) throws Exception {
    long requestId = supportOn(cookie, batchId);
    operatorService.quote(requestId, 220_000, "원본 1종, 최대 5만레코드");
    return requestId;
  }

  protected long toAccepted(Cookie cookie, long batchId) throws Exception {
    long requestId = toQuoted(cookie, batchId);
    acceptQuote(cookie, requestId);
    return requestId;
  }

  protected long toPaid(Cookie cookie, long batchId) throws Exception {
    long requestId = supportOn(cookie, batchId);
    operatorService.quote(requestId, 220_000, null);
    JsonNode order = acceptQuote(cookie, requestId);
    confirmPayment(cookie, requestId, order);
    return requestId;
  }

  protected long toProcessing(Cookie cookie, long batchId, long operatorId) throws Exception {
    long requestId = toPaid(cookie, batchId);
    operatorService.start(requestId, operatorId);
    return requestId;
  }

  protected long toReview(Cookie cookie, long batchId, long operatorId) throws Exception {
    long requestId = toProcessing(cookie, batchId, operatorId);
    submitMapping(requestId, operatorId, CUSTOMER_MAPPING);
    operatorService.submitReview(requestId, operatorId);
    return requestId;
  }

  protected static ImportMappingConfirmRequest mappingRequest(String mappingJson) {
    JsonNode body = JsonMapper.builder().build().readTree(mappingJson);
    return ImportRequestParser.mappingConfirm(body);
  }

  protected void submitMapping(long requestId, long operatorId, String mappingJson) {
    operatorService.saveMapping(requestId, operatorId, mappingRequest(mappingJson));
  }

  protected void approve(Cookie cookie, long requestId) throws Exception {
    mockMvc
        .perform(
            postWithVersion(
                "/api/import-support-requests/" + requestId + "/approve",
                null,
                cookie,
                supportVersion(requestId)))
        .andExpect(status().isOk());
  }
}
