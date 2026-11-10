package com.sairo.be.domain.customer.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.CustomerIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

@IntegrationTest
class CustomerConsultationIntegrationTest extends CustomerIntegrationTestBase {

  private static final String AT = "2026-09-05T14:00:00+09:00";

  private String body(String type, String content) {
    return "{\"occurredAt\":\""
        + AT
        + "\",\"content\":\""
        + content
        + "\",\"consultationType\":\""
        + type
        + "\"}";
  }

  private long consult(Cookie cookie, long customerId, String type, String content)
      throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        "/api/customers/" + customerId + "/consultations",
                        body(type, content),
                        cookie))
                .andExpect(status().isCreated())
                .andReturn())
        .get("consultationId")
        .longValue();
  }

  @Test
  void 상담을_등록하면_작성자를_서버가_기록하고_버전이_1이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    long customer = createCustomer(cookieOf(owner), "상담고객", null, owner.membership().getId());
    mockMvc
        .perform(
            jsonPostAs(
                "/api/customers/" + customer + "/consultations",
                body("FIRST_INQUIRY", "월세 문의"),
                cookieOf(agent)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.authorMembershipId").value(agent.membership().getId()))
        .andExpect(jsonPath("$.customerId").value(customer))
        .andExpect(jsonPath("$.consultationType").value("FIRST_INQUIRY"))
        .andExpect(jsonPath("$.occurredAt").value(AT))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()));
    assertThat(customerVersion(customer)).isEqualTo(1);
  }

  @Test
  void 등록의_잘못된_요청은_정해진_오류를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = createCustomer(cookie, "검증", null, owner.membership().getId());
    String url = "/api/customers/" + customer + "/consultations";
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url,
                "{\"occurredAt\":\""
                    + AT
                    + "\",\"content\":\"x\",\"consultationType\":\"FOLLOWUP\",\"authorMembershipId\":1}",
                cookie)),
        "authorMembershipId",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(url, "{\"content\":\"x\",\"consultationType\":\"FOLLOWUP\"}", cookie)),
        "occurredAt",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url, "{\"occurredAt\":\"" + AT + "\",\"consultationType\":\"FOLLOWUP\"}", cookie)),
        "content",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(url, "{\"occurredAt\":\"" + AT + "\",\"content\":\"x\"}", cookie)),
        "consultationType",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url,
                "{\"occurredAt\":\"어제\",\"content\":\"x\",\"consultationType\":\"FOLLOWUP\"}",
                cookie)),
        "occurredAt",
        "INVALID_FORMAT");
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, body("CALL", "x"), cookie)),
        "consultationType",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(jsonPostAs(url, body("FOLLOWUP", "x"), cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPostAs("/api/customers/99999/consultations", body("FOLLOWUP", "x"), cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", customer);
    assertError(
        mockMvc.perform(jsonPostAs(url, body("FOLLOWUP", "x"), cookie)),
        ErrorCode.CUSTOMER_ARCHIVED);
  }

  @Test
  void 최초문의는_고객당_하나이고_병합_집합_전체에_적용한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long a = createCustomer(cookie, "남는", null, m);
    long b = createCustomer(cookie, "합쳐진", null, m);
    consult(cookie, b, "FIRST_INQUIRY", "합쳐진 쪽 최초문의");
    mergeRow(a, b, m);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers/" + a + "/consultations", body("FIRST_INQUIRY", "또"), cookie)),
        ErrorCode.FIRST_INQUIRY_ALREADY_EXISTS);
    mockMvc
        .perform(
            jsonPostAs("/api/customers/" + b + "/consultations", body("FOLLOWUP", "후속"), cookie))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.customerId").value(a));
    long other = createCustomer(cookie, "다른", null, m);
    consult(cookie, other, "FIRST_INQUIRY", "다른 고객 최초");
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers/" + other + "/consultations", body("FIRST_INQUIRY", "중복"), cookie)),
        ErrorCode.FIRST_INQUIRY_ALREADY_EXISTS);
  }

  @Test
  void 목록은_병합_집합의_상담을_모으고_병합된_고객_경로는_빈_목록이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long a = createCustomer(cookie, "남는", null, m);
    long b = createCustomer(cookie, "합쳐진", null, m);
    consult(cookie, a, "FOLLOWUP", "A 상담");
    long bConsult = consult(cookie, b, "FIRST_INQUIRY", "B 상담");
    mergeRow(a, b, m);
    mockMvc
        .perform(authedGet("/api/customers/" + a + "/consultations", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].authorName").value(owner.user().getName()))
        .andExpect(jsonPath("$.items[0].revisionCount").value(0))
        .andExpect(jsonPath("$.items[0].version").value(1))
        .andExpect(jsonPath("$.items[0].officeId").value(owner.office().getId()));
    mockMvc
        .perform(authedGet("/api/customers/" + b + "/consultations", cookie))
        .andExpect(jsonPath("$.items.length()").value(0));
    assertError(
        mockMvc.perform(authedGet("/api/customers/" + a + "/consultations", cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(bConsult).isPositive();
  }

  @Test
  void 정정하면_이전_값을_이력에_보존하고_상담_버전을_올린다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long customer = createCustomer(cookie, "정정", null, owner.membership().getId());
    long id = consult(cookie, customer, "FOLLOWUP", "월세 문의");
    String url = "/api/customer-consultations/" + id;
    mockMvc
        .perform(
            jsonPatchAs(
                url,
                "{\"content\":\"전세 문의로 정정\",\"occurredAt\":\"2026-09-05T15:00:00+09:00\",\"correctionReason\":\"입력 오류\"}",
                cookieOf(agent),
                1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("전세 문의로 정정"))
        .andExpect(jsonPath("$.occurredAt").value("2026-09-05T15:00:00+09:00"))
        .andExpect(jsonPath("$.authorMembershipId").value(owner.membership().getId()))
        .andExpect(jsonPath("$.version").value(2));
    mockMvc
        .perform(authedGet("/api/customer-consultations/" + id + "/revisions", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].previousContent").value("월세 문의"))
        .andExpect(jsonPath("$.items[0].previousOccurredAt").value(AT))
        .andExpect(jsonPath("$.items[0].previousConsultationType").value("FOLLOWUP"))
        .andExpect(jsonPath("$.items[0].correctedBy").value(agent.user().getName()))
        .andExpect(jsonPath("$.items[0].correctionReason").value("입력 오류"))
        .andExpect(jsonPath("$.items[0].correctedAt").isString());
    mockMvc
        .perform(authedGet("/api/customers/" + customer + "/consultations", cookie))
        .andExpect(jsonPath("$.items[0].revisionCount").value(1))
        .andExpect(jsonPath("$.items[0].version").value(2));
  }

  @Test
  void 같은_값으로_정정하면_이력과_버전을_늘리지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = createCustomer(cookie, "동일", null, owner.membership().getId());
    long id = consult(cookie, customer, "FOLLOWUP", "그대로");
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/customer-consultations/" + id,
                "{\"content\":\"그대로\",\"correctionReason\":\"확인\"}",
                cookie,
                1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer_consultation_revision", Long.class))
        .isZero();
  }

  @Test
  void 정정을_최초문의로_바꾸면_집합의_다른_최초문의가_있을_때_409이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = createCustomer(cookie, "유형", null, owner.membership().getId());
    consult(cookie, customer, "FIRST_INQUIRY", "최초");
    long followup = consult(cookie, customer, "FOLLOWUP", "후속");
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/customer-consultations/" + followup,
                "{\"consultationType\":\"FIRST_INQUIRY\",\"correctionReason\":\"유형 정정\"}",
                cookie,
                1)),
        ErrorCode.FIRST_INQUIRY_ALREADY_EXISTS);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer_consultation_revision", Long.class))
        .isZero();
  }

  @Test
  void 정정의_잘못된_요청은_정해진_오류를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = createCustomer(cookie, "오류", null, owner.membership().getId());
    long id = consult(cookie, customer, "FOLLOWUP", "원문");
    String url = "/api/customer-consultations/" + id;
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"content\":\"x\"}", cookie, 1)),
        "correctionReason",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"correctionReason\":\"사유\"}", cookie, 1)),
        "body",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPatchAs(
                url,
                "{\"content\":\"x\",\"correctionReason\":\"사유\",\"authorMembershipId\":3}",
                cookie,
                1)),
        "authorMembershipId",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(
            jsonPatchAs(url, "{\"content\":\"x\",\"correctionReason\":\"사유\"}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            jsonPatchAs(url, "{\"content\":\"x\",\"correctionReason\":\"사유\"}", cookie, 4)),
        ErrorCode.VERSION_CONFLICT);
    mockMvc
        .perform(jsonPatchAs(url, "{\"content\":\"x\",\"correctionReason\":\"사유\"}", cookie, 4))
        .andExpect(jsonPath("$.currentVersion").value(1));
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                url, "{\"content\":\"x\",\"correctionReason\":\"사유\"}", cookieOf(other), 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/customer-consultations/99999",
                "{\"content\":\"x\",\"correctionReason\":\"사유\"}",
                cookie,
                1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            authedGet("/api/customer-consultations/" + id + "/revisions", cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet("/api/customer-consultations/99999/revisions", cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", customer);
    assertError(
        mockMvc.perform(
            jsonPatchAs(url, "{\"content\":\"x\",\"correctionReason\":\"사유\"}", cookie, 1)),
        ErrorCode.CUSTOMER_ARCHIVED);
  }

  @Test
  void 상담_엔드포인트는_로그인_소속_역할을_검사한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long customer = createCustomer(cookieOf(owner), "권한", null, owner.membership().getId());
    long id = consult(cookieOf(owner), customer, "FOLLOWUP", "원문");
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember@example.com", "010-1212-3434"));
    Cookie deputy = cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY));
    java.util.List<
            java.util.function.Function<
                Cookie, org.springframework.test.web.servlet.RequestBuilder>>
        requests =
            java.util.List.of(
                c -> authedGet("/api/customers/" + customer + "/consultations", c),
                c ->
                    jsonPostAs(
                        "/api/customers/" + customer + "/consultations", body("FOLLOWUP", "x"), c),
                c ->
                    jsonPatchAs(
                        "/api/customer-consultations/" + id,
                        "{\"content\":\"x\",\"correctionReason\":\"사유\"}",
                        c,
                        1),
                c -> authedGet("/api/customer-consultations/" + id + "/revisions", c));
    for (var request : requests) {
      assertError(mockMvc.perform(request.apply(null)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(request.apply(plain)), ErrorCode.FORBIDDEN);
      assertError(mockMvc.perform(request.apply(deputy)), ErrorCode.FORBIDDEN);
    }
  }
}
