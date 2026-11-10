package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.ImportIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportQueryIntegrationTest extends ImportIntegrationTestBase {

  @Test
  void 목록은_최신순으로_이력과_이번_달_잔여_횟수를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long first = readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,김철수,,,");
    executeBatch(cookie, first);
    long second = uploadCustomers(cookie, "C-9,이영희,,,");

    mockMvc
        .perform(authedGet("/api/import-batches", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.remainingThisMonth").value(4))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].batchId").value(second))
        .andExpect(jsonPath("$.items[0].status").value("UPLOADED"))
        .andExpect(jsonPath("$.items[0].rowCount").doesNotExist())
        .andExpect(jsonPath("$.items[0].executedAt").doesNotExist())
        .andExpect(jsonPath("$.items[0].cancellableUntil").doesNotExist())
        .andExpect(jsonPath("$.items[0].supportRequestId").doesNotExist())
        .andExpect(jsonPath("$.items[0].supportRequestStatus").doesNotExist())
        .andExpect(jsonPath("$.items[0].eligibleForSupport").value(true))
        .andExpect(jsonPath("$.items[1].batchId").value(first))
        .andExpect(jsonPath("$.items[1].fileName").value("고객.csv"))
        .andExpect(jsonPath("$.items[1].targetType").value("CUSTOMER"))
        .andExpect(jsonPath("$.items[1].status").value("COMPLETED"))
        .andExpect(jsonPath("$.items[1].rowCount").value(2))
        .andExpect(jsonPath("$.items[1].executedAt").exists())
        .andExpect(jsonPath("$.items[1].cancellableUntil").exists())
        .andExpect(jsonPath("$.items[1].eligibleForSupport").value(false))
        .andExpect(jsonPath("$.items[1].officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.items[1].createdAt").exists())
        .andExpect(jsonPath("$.items[1].updatedAt").exists())
        .andExpect(jsonPath("$.items[1].version").value(batchVersion(first)));
  }

  @Test
  void 목록은_연결된_유료_이전_지원의_상태와_신청_가능_여부를_보여준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long linked = uploadCustomers(cookie, "C-1,홍길동,,,");
    long free = uploadCustomers(cookie, "C-2,김철수,,,");
    long cancelledLink = uploadCustomers(cookie, "C-3,이영희,,,");
    long requestId = insertSupportRequest(owner.office().getId(), linked, "QUOTED");
    long cancelled = insertSupportRequest(owner.office().getId(), cancelledLink, "REQUESTED");
    jdbcTemplate.update(
        "UPDATE import_support_request SET status = 'CANCELLED', cancelled_at = now(),"
            + " cancelled_by_membership_id = ? WHERE id = ?",
        owner.membership().getId(),
        cancelled);

    JsonNode body =
        AuthFlowSupport.json(mockMvc.perform(authedGet("/api/import-batches", cookie)).andReturn());

    Map<Long, JsonNode> byId = new LinkedHashMap<>();
    body.get("items").forEach(item -> byId.put(item.get("batchId").longValue(), item));
    assertThat(byId.get(linked).get("supportRequestId").longValue()).isEqualTo(requestId);
    assertThat(byId.get(linked).get("supportRequestStatus").asString()).isEqualTo("QUOTED");
    assertThat(byId.get(linked).get("eligibleForSupport").asBoolean()).isFalse();
    assertThat(byId.get(free).get("supportRequestId").isNull()).isTrue();
    assertThat(byId.get(free).get("eligibleForSupport").asBoolean()).isTrue();
    assertThat(byId.get(cancelledLink).get("supportRequestId").isNull()).isTrue();
    assertThat(byId.get(cancelledLink).get("eligibleForSupport").asBoolean()).isTrue();
  }

  @Test
  void 목록은_다른_사무소의_이력을_보여주지_않고_횟수를_다_쓰면_잔여가_0이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    useUpMonthlyQuota(other, 2);
    uploadCustomers(cookieOf(other), "C-1,홍길동,,,");
    useUpMonthlyQuota(owner, 7);

    mockMvc
        .perform(authedGet("/api/import-batches", cookieOf(owner)))
        .andExpect(jsonPath("$.items.length()").value(7))
        .andExpect(jsonPath("$.remainingThisMonth").value(0));
    mockMvc
        .perform(authedGet("/api/import-batches", cookieOf(other)))
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.remainingThisMonth").value(3));
  }

  @Test
  void 상세는_업로드_직후에는_counts가_null이고_시트_목록을_다시_읽을_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.batchId").value(batchId))
        .andExpect(jsonPath("$.fileName").value("고객.csv"))
        .andExpect(jsonPath("$.targetType").value("CUSTOMER"))
        .andExpect(jsonPath("$.status").value("UPLOADED"))
        .andExpect(jsonPath("$.rowCount").doesNotExist())
        .andExpect(jsonPath("$.counts").doesNotExist())
        .andExpect(jsonPath("$.executedAt").doesNotExist())
        .andExpect(jsonPath("$.cancellableUntil").doesNotExist())
        .andExpect(jsonPath("$.cancelledAt").doesNotExist())
        .andExpect(jsonPath("$.failureReason").doesNotExist())
        .andExpect(jsonPath("$.cancelSummary").doesNotExist())
        .andExpect(jsonPath("$.sheets[0].name").value("Sheet1"))
        .andExpect(jsonPath("$.sheets[0].rowCount").value(1))
        .andExpect(jsonPath("$.sheets[0].columnCount").value(5))
        .andExpect(jsonPath("$.selectedSheetName").value("Sheet1"))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void 실행_중_상세는_행_결과로_진행_건수를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,김철수,,,", "C-3,이영희,,,");
    forceStatus(batchId, "EXECUTING");
    jdbcTemplate.update(
        "INSERT INTO import_row_result (batch_id, office_id, source_row_number, result,"
            + " failure_reason) VALUES (?, ?, 2, 'SUCCEEDED', NULL), (?, ?, 3, 'FAILED', '사유')",
        batchId,
        owner.office().getId(),
        batchId,
        owner.office().getId());

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId, cookie))
        .andExpect(jsonPath("$.status").value("EXECUTING"))
        .andExpect(jsonPath("$.counts.total").value(3))
        .andExpect(jsonPath("$.counts.succeeded").value(1))
        .andExpect(jsonPath("$.counts.failed").value(1))
        .andExpect(jsonPath("$.counts.unprocessed").value(1));
  }

  @Test
  void 상세와_목록은_권한과_사무소_경계를_지킨다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    long batchId = uploadCustomers(cookieOf(owner), "C-1,홍길동,,,");
    String url = "/api/import-batches/" + batchId;

    assertError(mockMvc.perform(authedGet(url, cookieOf(other))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(authedGet(url, cookieOf(agent))), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(authedGet(url, null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet("/api/import-batches", cookieOf(agent))), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(authedGet("/api/import-batches", null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet("/api/import-batches/99999", cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 모든_이전_엔드포인트는_대표만_호출할_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    long batchId = readyCustomerBatch(cookieOf(owner), "C-1,홍길동,,,");
    String base = "/api/import-batches/" + batchId;
    Map<String, Function<Cookie, RequestBuilder>> endpoints = new LinkedHashMap<>();
    endpoints.put(
        "POST /api/import-batches",
        c -> uploadRequest(c, "a.csv", customerCsv("1,a,,,"), "CUSTOMER"));
    endpoints.put("GET /api/import-batches", c -> authedGet("/api/import-batches", c));
    endpoints.put("GET /{id}", c -> authedGet(base, c));
    endpoints.put(
        "PUT /{id}/sheet", c -> jsonPutAs(base + "/sheet", "{\"sheetName\":\"Sheet1\"}", c, 1));
    endpoints.put("GET /{id}/mapping-suggestion", c -> authedGet(base + "/mapping-suggestion", c));
    endpoints.put("PUT /{id}/mapping", c -> jsonPutAs(base + "/mapping", CUSTOMER_MAPPING, c, 1));
    endpoints.put("GET /{id}/preview", c -> authedGet(base + "/preview", c));
    endpoints.put("POST /{id}/execute", c -> jsonPostAs(base + "/execute", "", c));
    endpoints.put("POST /{id}/cancel", c -> jsonPostAs(base + "/cancel", "", c));
    endpoints.put("POST /{id}/retry-failed", c -> jsonPostAs(base + "/retry-failed", "", c));

    for (Map.Entry<String, Function<Cookie, RequestBuilder>> entry : endpoints.entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(null)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(entry.getValue().apply(cookieOf(agent))), ErrorCode.FORBIDDEN);
      assertError(mockMvc.perform(entry.getValue().apply(cookieOf(deputy))), ErrorCode.FORBIDDEN);
    }
    assertThat(batchStatus(batchId)).isEqualTo("MAPPING");
    assertThat(count("customer")).isZero();
  }
}
