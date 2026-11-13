package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class PrivacyRequestVersionIntegrationTest extends OpsConsoleTestBase {

  private static final String FORWARD = "{\"action\":\"FORWARD\",\"note\":\"책임 사무소에 전달\"}";

  private Cookie privacySession;
  private long requestId;
  private String detailPath;

  @BeforeEach
  void setUp() {
    privacySession = sessionOf(operator("개인정보담당", OperatorRole.PRIVACY));
    requestId =
        jdbcTemplate.queryForObject(
            "INSERT INTO privacy_request (requester_user_id, request_type, identity_status,"
                + " legal_basis, legal_due_at, internal_due_at, request_scope, status) VALUES (?,"
                + " 'ACCESS', 'PENDING', '개인정보 보호법 제35조', now() + interval '10 days', now() +"
                + " interval '7 days', '본인 계정 정보 열람', 'RECEIVED') RETURNING id",
            Long.class,
            staff(OfficeMembershipRole.OWNER).user().getId());
    detailPath = "/api/ops/privacy-requests/" + requestId;
  }

  private ResultActions process(String ifMatch) throws Exception {
    return process(FORWARD, ifMatch);
  }

  private ResultActions process(String body, String ifMatch) throws Exception {
    var request = jsonPost(detailPath + "/process", body, privacySession);
    return mockMvc.perform(ifMatch == null ? request : request.header("If-Match", ifMatch));
  }

  private long storedVersion() {
    return count("SELECT version FROM privacy_request WHERE id = ?", requestId);
  }

  private String storedStatus() {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM privacy_request WHERE id = ?", String.class, requestId);
  }

  @Test
  void 상세의_version은_저장된_변경_번호이고_처리가_성공하면_1_오른다() throws Exception {
    mockMvc
        .perform(get(detailPath).cookie(privacySession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));

    process("1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FORWARDED"));

    assertThat(storedVersion()).isEqualTo(2);
    mockMvc
        .perform(get(detailPath).cookie(privacySession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    process("{\"action\":\"REQUEST_COOPERATION\",\"note\":\"협조 요청\"}", "\"2\"")
        .andExpect(status().isOk());
    assertThat(storedVersion()).isEqualTo(3);
  }

  @Test
  void If_Match가_현재_버전과_다르면_409이고_요청은_바뀌지_않는다() throws Exception {
    assertError(process("2"), ErrorCode.VERSION_CONFLICT)
        .andExpect(jsonPath("$.currentVersion").value(1));

    assertThat(storedVersion()).isEqualTo(1);
    assertThat(storedStatus()).isEqualTo("RECEIVED");
    assertThat(
            count(
                "SELECT count(*) FROM privacy_request_event WHERE privacy_request_id = ?",
                requestId))
        .isZero();
    assertThat(auditCount("PRIVACY_REQUEST_PROCESSED")).isZero();

    process("1").andExpect(status().isOk());
    assertError(process("1"), ErrorCode.VERSION_CONFLICT)
        .andExpect(jsonPath("$.currentVersion").value(2));
    assertThat(storedVersion()).isEqualTo(2);
  }

  @Test
  void If_Match가_없거나_형식이_틀리면_400이고_요청은_바뀌지_않는다() throws Exception {
    assertFieldError(process(null), "If-Match", "REQUIRED");
    assertFieldError(process("abc"), "If-Match", "INVALID_FORMAT");
    assertFieldError(process("0"), "If-Match", "OUT_OF_RANGE");

    assertThat(storedVersion()).isEqualTo(1);
    assertThat(storedStatus()).isEqualTo("RECEIVED");
  }

  @Test
  void 변경_번호는_0_이하로_저장할_수_없다() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE privacy_request SET version = 0 WHERE id = ?", requestId))
        .hasMessageContaining("privacy_request_version_check");
  }
}
