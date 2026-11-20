package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.ImportIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportRowResultIntegrationTest extends ImportIntegrationTestBase {

  private JsonNode rows(Cookie cookie, long batchId, String query) throws Exception {
    MvcResult result =
        mockMvc
            .perform(authedGet("/api/import-batches/" + batchId + "/rows" + query, cookie))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result);
  }

  private long executedWithFailure(Cookie cookie) throws Exception {
    long batchId =
        readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,김철수,,이메일아님,", "C-3,이영희,,,", "C-4,,,,");
    executeBatch(cookie, batchId);
    return batchId;
  }

  @Test
  void 성공과_실패를_나누어_줄_번호_원인_고치는_방법을_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = executedWithFailure(cookie);

    JsonNode all = rows(cookie, batchId, "");
    assertThat(all.get("summary").get("succeeded").asInt()).isEqualTo(2);
    assertThat(all.get("summary").get("failed").asInt()).isEqualTo(2);
    assertThat(all.get("totalElements").asInt()).isEqualTo(4);

    JsonNode succeeded = rows(cookie, batchId, "?outcome=SUCCEEDED");
    assertThat(succeeded.get("rows")).hasSize(2);
    assertThat(succeeded.get("rows").get(0).get("rowNumber").asInt()).isEqualTo(2);
    assertThat(succeeded.get("rows").get(0).get("label").asString()).isEqualTo("홍길동");
    assertThat(succeeded.get("rows").get(0).get("targetId").isNull()).isFalse();

    JsonNode failed = rows(cookie, batchId, "?outcome=FAILED");
    assertThat(failed.get("rows")).hasSize(2);
    JsonNode emailRow = failed.get("rows").get(0);
    assertThat(emailRow.get("rowNumber").asInt()).isEqualTo(3);
    assertThat(emailRow.get("issues").get(0).get("code").asString())
        .isEqualTo("INVALID_EMAIL_FORMAT");
    assertThat(emailRow.get("issues").get(0).get("fieldLabel").asString()).isEqualTo("이메일");
    assertThat(emailRow.get("issues").get(0).get("fix").asString()).contains("name@example.com");
    assertThat(emailRow.get("issues").get(0).get("correctable").asBoolean()).isTrue();
    JsonNode nameRow = failed.get("rows").get(1);
    assertThat(nameRow.get("issues").get(0).get("code").asString())
        .isEqualTo("REQUIRED_VALUE_MISSING");
    assertThat(nameRow.get("issues").get(0).get("fix").asString()).contains("고객명");
  }

  @Test
  void 앱에서_고친_뒤_실패한_줄만_다시_처리하면_중복_없이_성공한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = executedWithFailure(cookie);
    String base = "/api/import-batches/" + batchId;

    MvcResult corrected =
        mockMvc
            .perform(
                jsonPutAs(
                    base + "/rows/3/correction",
                    "{\"values\":{\"customerEmail\":\"fixed@example.com\"}}",
                    cookie,
                    null))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode row = AuthFlowSupport.json(corrected);
    assertThat(row.get("readyToRetry").asBoolean()).isTrue();
    assertThat(row.get("issues")).isEmpty();

    mockMvc
        .perform(
            jsonPutAs(
                base + "/rows/5/correction",
                "{\"values\":{\"customerName\":\"박민수\"}}",
                cookie,
                null))
        .andExpect(status().isOk());
    mockMvc.perform(jsonPostAs(base + "/retry-failed", "", cookie)).andExpect(status().isOk());

    JsonNode detail = detail(cookie, batchId);
    assertThat(detail.get("counts").get("succeeded").asInt()).isEqualTo(4);
    assertThat(detail.get("counts").get("failed").asInt()).isZero();
    assertThat(count("customer")).isEqualTo(4);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT email FROM customer WHERE name = '김철수'", String.class))
        .isEqualTo("fixed@example.com");

    mockMvc.perform(jsonPostAs(base + "/retry-failed", "", cookie));
    assertThat(count("customer")).isEqualTo(4);
  }

  @Test
  void 고쳐도_틀리면_계속_실패로_남고_원인이_갱신된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = executedWithFailure(cookie);

    MvcResult result =
        mockMvc
            .perform(
                jsonPutAs(
                    "/api/import-batches/" + batchId + "/rows/3/correction",
                    "{\"values\":{\"customerEmail\":\"또틀림\"}}",
                    cookie,
                    null))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode row = AuthFlowSupport.json(result);
    assertThat(row.get("readyToRetry").asBoolean()).isFalse();
    assertThat(row.get("issues").get(0).get("code").asString()).isEqualTo("INVALID_EMAIL_FORMAT");
  }

  @Test
  void 실패가_아닌_줄과_연결하지_않은_항목과_상태를_거절한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = executedWithFailure(cookie);
    String url = "/api/import-batches/" + batchId + "/rows/";

    assertError(
        mockMvc.perform(
            jsonPutAs(url + "2/correction", "{\"values\":{\"customerName\":\"가\"}}", cookie, null)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(
            jsonPutAs(url + "3/correction", "{\"values\":{\"nothing\":\"가\"}}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(jsonPutAs(url + "3/correction", "{\"values\":{}}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            jsonPutAs(
                url + "99/correction", "{\"values\":{\"customerName\":\"가\"}}", cookie, null)),
        ErrorCode.RESOURCE_NOT_FOUND);
    long uploaded = uploadCustomers(cookie, "C-9,최,,,");
    assertError(
        mockMvc.perform(authedGet("/api/import-batches/" + uploaded + "/rows", cookie)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(authedGet("/api/import-batches/" + batchId + "/rows?size=101", cookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(authedGet("/api/import-batches/" + batchId + "/rows?outcome=X", cookie)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 실패한_줄_CSV는_원본_열에_원인을_붙이고_수식을_무력화한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,=cmd|x,,이메일아님,\"메모,쉼표\"");
    executeBatch(cookie, batchId);

    MvcResult result =
        mockMvc
            .perform(authedGet("/api/import-batches/" + batchId + "/failed-rows.csv", cookie))
            .andExpect(status().isOk())
            .andReturn();
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(body).startsWith("﻿원본 행 번호,고객번호,성명");
    assertThat(body).contains("실패 원인,고치는 방법");
    assertThat(body).contains("3,C-2,'=cmd|x");
    assertThat(body).contains("\"메모,쉼표\"");
    assertThat(body).contains("이메일 형식이 올바르지 않습니다.");
    assertThat(body).doesNotContain("홍길동");
  }

  @Test
  void 권한과_다른_사무소_경계를_지킨다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    long batchId = executedWithFailure(cookieOf(owner));
    String url = "/api/import-batches/" + batchId + "/rows";

    assertError(mockMvc.perform(authedGet(url, cookieOf(other))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(authedGet(url, cookieOf(agent))), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(authedGet(url, null)), ErrorCode.UNAUTHENTICATED);
  }
}
