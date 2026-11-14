package com.sairo.be.scenario;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsIntegrationTestBase;
import com.sairo.be.support.fixture.BillingFixtures;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** 개발플로우 4장 시나리오 ①을 실제 엔드포인트로 잇는 출시 검증 시험이다. */
@IntegrationTest
class LaunchScenarioSignupIntegrationTest extends OpsIntegrationTestBase {

  private static final String EMAIL = "launch-owner@example.com";
  private static final String PHONE = "010-3333-4444";
  private static final byte[] PDF = "%PDF-1.4 evidence".getBytes(StandardCharsets.ISO_8859_1);
  private static final String[] EVIDENCE = {
    "BROKERAGE_LICENSE", "BUSINESS_CERTIFICATE", "REPRESENTATIVE_QUALIFICATION"
  };
  private static final String[] CHECKLIST = {
    "REVISION_MATCH",
    "SOURCE_VERIFIED",
    "REGISTRATION_RELATION",
    "QUALIFICATION_EDUCATION",
    "EMPLOYMENT_ROSTER"
  };

  private static String registrationBody() {
    return "{\"entityType\":\"INDIVIDUAL\",\"registrationAuthority\":\"서울특별시 종로구\","
        + "\"realEstateLicenseNumber\":\"LAUNCH-1\",\"businessRegistrationNumber\":\"1234567890\","
        + "\"requestedOfficeName\":\"사이로공인중개사\",\"requestedRepresentativeName\":\"홍대표\","
        + "\"requestedRepresentativePhone\":\"02-123-4567\",\"requestedAddress\":\"서울 종로구 1\","
        + "\"applicantRegistrationRelation\":\"REPRESENTATIVE\"}";
  }

  private MockMultipartHttpServletRequestBuilder evidence(long registrationId, Cookie cookie) {
    MockMultipartHttpServletRequestBuilder builder =
        multipart("/api/office-registrations/{id}/evidence", registrationId);
    for (String type : EVIDENCE) {
      builder.param("evidenceType", type);
      builder.file(new MockMultipartFile("file", type + ".pdf", "application/pdf", PDF));
    }
    builder.cookie(new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE), cookie);
    builder.header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE);
    return builder;
  }

  private String checklistBody() {
    StringBuilder items = new StringBuilder();
    for (String code : CHECKLIST) {
      if (items.length() > 0) {
        items.append(',');
      }
      items.append("{\"itemCode\":\"").append(code).append("\",\"checked\":true}");
    }
    return "{\"items\":[" + items + "]}";
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }

  @Test
  void 시나리오1_이메일_가입_사무소_등록_신청_운영자_승인_뒤_로그인하면_대표로_들어간다() throws Exception {
    long trialEvent =
        new BillingFixtures(jdbcTemplate)
            .trialEvent(
                "GENERAL",
                "DURATION",
                Instant.now().minus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(30)),
                null,
                "ACTIVE");

    // 1. 이메일·휴대폰 인증 뒤 이메일 가입
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);
    String phoneTicket = flow.sendAndVerifyPhone(PHONE);
    MvcResult signup =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/signup/email",
                    signupBody(emailTicket, phoneTicket, PASSWORD, "홍대표", requiredTerms())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"))
            .andReturn();
    Cookie applicant = AuthFlowSupport.sessionCookie(signup);
    assertThat(applicant).isNotNull();
    long userId = count("SELECT id FROM app_user WHERE email = ?", EMAIL);

    // 2. 사무소 등록 신청 작성과 증빙 제출
    long registrationId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(jsonPost("/api/office-registrations", registrationBody(), applicant))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andReturn())
            .get("registrationId")
            .longValue();
    mockMvc
        .perform(evidence(registrationId, applicant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    mockMvc
        .perform(get("/api/auth/context").cookie(applicant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessCondition").value("OFFICE_UNDER_REVIEW"))
        .andExpect(jsonPath("$.pendingRegistrationId").value(registrationId))
        .andExpect(jsonPath("$.officeId").doesNotExist());
    mockMvc
        .perform(get("/api/offices/current").cookie(applicant))
        .andExpect(status().isForbidden());

    // 3. 심사 운영자가 목록에서 신청을 찾고 확인 항목을 채운 뒤 승인
    Operator reviewer = createOperator("launch-review", "김심사", OperatorRole.REVIEW);
    Cookie review = operatorSession(reviewer);
    mockMvc
        .perform(get("/api/ops/office-registrations").cookie(review))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].registrationId").value(registrationId))
        .andExpect(jsonPath("$.items[0].status").value("PENDING_REVIEW"));
    mockMvc
        .perform(
            AuthFlowSupport.withCsrf(
                put("/api/ops/office-registrations/" + registrationId + "/checklist")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(checklistBody())
                    .cookie(review)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canApprove").value(true));
    long officeId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPost(
                            "/api/ops/office-registrations/" + registrationId + "/approve",
                            "",
                            review))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andReturn())
            .get("officeId")
            .longValue();

    Map<String, Object> membership =
        jdbcTemplate.queryForMap(
            "SELECT role, status, user_id FROM office_membership WHERE office_id = ?", officeId);
    assertThat(membership)
        .containsEntry("role", "OWNER")
        .containsEntry("status", "APPROVED")
        .containsEntry("user_id", userId);
    assertThat(
            count(
                "SELECT count(DISTINCT to_status) FROM office_registration_status_history WHERE"
                    + " registration_id = ?"
                    + " AND to_status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED')",
                registrationId))
        .isEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM ops_audit_log WHERE action_type ="
                    + " 'OFFICE_REGISTRATION_APPROVED' AND operator_account_id = ?",
                reviewer.id()))
        .isEqualTo(1);

    // 4. 로그아웃 뒤 이메일 로그인하면 승인된 사무소의 대표로 판정된다
    mockMvc
        .perform(jsonPost("/api/auth/logout", "", applicant))
        .andExpect(status().is2xxSuccessful());
    MvcResult login =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/login",
                    "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.officeId").value(officeId))
            .andReturn();
    Cookie session = AuthFlowSupport.sessionCookie(login);
    mockMvc
        .perform(get("/api/auth/context").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.officeId").value(officeId))
        .andExpect(jsonPath("$.role").value("OWNER"))
        .andExpect(jsonPath("$.accessCondition").value("ACTIVE"))
        .andExpect(jsonPath("$.pendingRegistrationId").doesNotExist());
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT status, trial_event_id FROM subscription WHERE office_id = ?", officeId))
        .containsEntry("status", "TRIAL")
        .containsEntry("trial_event_id", trialEvent);
    mockMvc
        .perform(get("/api/offices/current").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("사이로공인중개사"));
    assertThat(count("SELECT count(*) FROM login_history WHERE user_id = ?", userId)).isPositive();
  }
}
