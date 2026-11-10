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

@IntegrationTest
class ImportSupportCreateIntegrationTest extends SupportIntegrationTestBase {

  private ResultActions post(Cookie cookie, String body) throws Exception {
    return mockMvc.perform(jsonPostAs("/api/import-support-requests", body, cookie));
  }

  private static String body(long batchId) {
    return "{\"importBatchId\":" + batchId + ",\"sourceSystem\":\"양셀\",\"estimatedRowCount\":3000}";
  }

  @Test
  void 업로드해_둔_실행_단위를_연결해_신청하면_REQUESTED로_접수한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,");

    post(cookie, body(batchId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").isNumber())
        .andExpect(jsonPath("$.importBatchId").value(batchId))
        .andExpect(jsonPath("$.status").value("REQUESTED"))
        .andExpect(jsonPath("$.quoteAmount").doesNotExist())
        .andExpect(jsonPath("$.updatedAt").exists())
        .andExpect(jsonPath("$.version").value(1));

    assertThat(count("import_support_request")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT estimated_row_count FROM import_support_request", Integer.class))
        .isEqualTo(3000);
  }

  @Test
  void 신청한_실행_단위는_직접_매핑할_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,");
    supportOn(cookie, batchId);

    assertError(
        mockMvc.perform(
            jsonPutAs(
                "/api/import-batches/" + batchId + "/mapping",
                CUSTOMER_MAPPING,
                cookie,
                batchVersion(batchId))),
        ErrorCode.IMPORT_MANAGED_BY_SUPPORT);
  }

  @Test
  void 이미_활성_신청에_연결된_실행_단위는_409_IMPORT_BATCH_ALREADY_LINKED이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,");
    supportOn(cookie, batchId);

    assertError(post(cookie, body(batchId)), ErrorCode.IMPORT_BATCH_ALREADY_LINKED);
    assertThat(count("import_support_request")).isEqualTo(1);
  }

  @Test
  void 취소한_신청의_실행_단위는_다시_신청할_수_있고_직접_매핑도_돌아온다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,");
    long first = supportOn(cookie, batchId);
    mockMvc
        .perform(
            postWithVersion(
                "/api/import-support-requests/" + first + "/cancel",
                "",
                cookie,
                supportVersion(first)))
        .andExpect(status().isOk());

    confirmMapping(cookie, batchId, CUSTOMER_MAPPING);
    assertThat(batchStatus(batchId)).isEqualTo("MAPPING");
    post(cookie, body(batchId)).andExpect(status().isOk());
    assertThat(count("import_support_request")).isEqualTo(2);
  }

  @Test
  void 신청할_수_없는_상태의_실행_단위는_409_INVALID_TRANSITION이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long executing = uploadCustomers(cookie, "C-1,홍길동,,,");
    forceStatus(executing, "EXECUTING");
    long completed = uploadCustomers(cookie, "C-2,김철수,,,");
    forceStatus(completed, "COMPLETED");

    assertError(post(cookie, body(executing)), ErrorCode.INVALID_TRANSITION);
    assertError(post(cookie, body(completed)), ErrorCode.INVALID_TRANSITION);
    assertThat(count("import_support_request")).isZero();
  }

  @Test
  void 매핑을_확정했거나_미리보기를_본_실행_단위도_신청할_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long mapped = readyCustomerBatch(cookie, "C-1,홍길동,,,");
    long previewed = readyCustomerBatch(cookie, "C-2,김철수,,,");
    forceStatus(previewed, "PREVIEW");

    post(cookie, body(mapped)).andExpect(status().isOk());
    post(cookie, body(previewed)).andExpect(status().isOk());
  }

  @Test
  void 다른_사무소의_실행_단위는_404_RESOURCE_NOT_FOUND이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    long otherBatch = uploadCustomers(cookieOf(other), "C-1,홍길동,,,");

    assertError(post(cookieOf(owner), body(otherBatch)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(post(cookieOf(owner), body(999_999)), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 필수_누락과_형식_오류와_허용되지_않는_필드는_400_INVALID_REQUEST이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,");

    assertFieldError(
        post(cookie, "{\"sourceSystem\":\"양셀\",\"estimatedRowCount\":3}"),
        "importBatchId",
        "REQUIRED");
    assertFieldError(
        post(cookie, "{\"importBatchId\":" + batchId + ",\"estimatedRowCount\":3}"),
        "sourceSystem",
        "REQUIRED");
    assertFieldError(
        post(cookie, "{\"importBatchId\":" + batchId + ",\"sourceSystem\":\"양셀\"}"),
        "estimatedRowCount",
        "REQUIRED");
    assertFieldError(
        post(
            cookie,
            "{\"importBatchId\":"
                + batchId
                + ",\"sourceSystem\":\"양셀\",\"estimatedRowCount\":\"많이\"}"),
        "estimatedRowCount",
        "TYPE_MISMATCH");
    assertFieldError(
        post(
            cookie,
            "{\"importBatchId\":" + batchId + ",\"sourceSystem\":\"양셀\",\"estimatedRowCount\":-1}"),
        "estimatedRowCount",
        "OUT_OF_RANGE");
    assertFieldError(
        post(
            cookie,
            "{\"importBatchId\":"
                + batchId
                + ",\"sourceSystem\":\"양셀\",\"estimatedRowCount\":3,\"quoteAmount\":1}"),
        "quoteAmount",
        "NOT_ALLOWED");
    assertThat(count("import_support_request")).isZero();
  }

  @Test
  void 로그인하지_않으면_401이고_대표가_아니면_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long batchId = uploadCustomers(cookieOf(owner), "C-1,홍길동,,,");

    assertError(post(null, body(batchId)), ErrorCode.UNAUTHENTICATED);
    assertError(post(cookieOf(agentOf(owner)), body(batchId)), ErrorCode.FORBIDDEN);
    assertError(
        post(cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY)), body(batchId)),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 목록은_자기_사무소의_신청만_최신순으로_페이지에_담아_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long first = supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long second = toQuoted(cookie, uploadCustomers(cookie, "C-2,김철수,,,"));
    supportOn(cookieOf(other), uploadCustomers(cookieOf(other), "C-3,박영희,,,"));

    mockMvc
        .perform(authedGet("/api/import-support-requests", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].requestId").value(second))
        .andExpect(jsonPath("$.items[0].status").value("QUOTED"))
        .andExpect(jsonPath("$.items[0].quoteAmount").value(220000))
        .andExpect(jsonPath("$.items[0].sourceSystem").value("양셀"))
        .andExpect(jsonPath("$.items[0].importBatchId").isNumber())
        .andExpect(jsonPath("$.items[0].updatedAt").exists())
        .andExpect(jsonPath("$.items[1].requestId").value(first))
        .andExpect(jsonPath("$.items[1].quoteAmount").doesNotExist());
  }

  @Test
  void 목록은_status와_페이지_크기로_거를_수_있고_잘못된_값은_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long quoted = toQuoted(cookie, uploadCustomers(cookie, "C-2,김철수,,,"));

    mockMvc
        .perform(authedGet("/api/import-support-requests?status=QUOTED&size=50", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.size").value(50))
        .andExpect(jsonPath("$.items[0].requestId").value(quoted));
    mockMvc
        .perform(authedGet("/api/import-support-requests?page=1", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(2));
    assertFieldError(
        mockMvc.perform(authedGet("/api/import-support-requests?status=NOPE", cookie)),
        "status",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/import-support-requests?size=10", cookie)),
        "size",
        "NOT_ALLOWED");
  }

  @Test
  void 목록은_로그인하지_않으면_401이고_대표가_아니면_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);

    assertError(
        mockMvc.perform(authedGet("/api/import-support-requests", null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet("/api/import-support-requests", cookieOf(agentOf(owner)))),
        ErrorCode.FORBIDDEN);
  }
}
