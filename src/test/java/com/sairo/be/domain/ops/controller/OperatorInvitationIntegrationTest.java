package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.json;
import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OperatorInvitationIntegrationTest extends OpsIntegrationTestBase {

  private static final String TOKEN = "invite-token-0001";
  private static final String KAKAO_ID = "7101001";

  private ResultActions post(String path, String body, Cookie... cookies) throws Exception {
    return mockMvc.perform(jsonPost("/api/ops/auth/invitations/" + path, body, cookies));
  }

  private String tokenBody(String token) {
    return "{\"token\":\"" + token + "\"}";
  }

  private OpsStart startKakao(String token) throws Exception {
    MvcResult result = post("kakao-start", tokenBody(token)).andExpect(status().isOk()).andReturn();
    String url = json(result).get("authorizationUrl").asString();
    Matcher matcher = Pattern.compile("state=([A-Za-z0-9_-]+)").matcher(url);
    assertThat(matcher.find()).isTrue();
    assertThat(url).endsWith("flow=OPERATOR_INVITATION");
    return new OpsStart(opsCookieOf(result), matcher.group(1), url);
  }

  private MvcResult callback(OpsStart start, String kakaoId, String state) throws Exception {
    return mockMvc
        .perform(
            get("/api/ops/auth/invitations/kakao/callback")
                .cookie(start.cookie())
                .param("code", kakaoId)
                .param("state", state))
        .andReturn();
  }

  private Cookie enrollmentCookie(String token, String kakaoId) throws Exception {
    OpsStart start = startKakao(token);
    MvcResult result = callback(start, kakaoId, start.state());
    assertThat(result.getResponse().getStatus()).isEqualTo(302);
    return opsCookieOf(result);
  }

  private String enroll(Cookie cookie) throws Exception {
    MvcResult result = post("mfa-enrollment", "", cookie).andExpect(status().isOk()).andReturn();
    return json(result).get("secret").asString();
  }

  @Test
  void 초대_확인은_이름_역할_만료시각만_돌려주고_초대자_정보는_주지_않는다() throws Exception {
    insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.SUPPORT, OperatorRole.BILLING);
    MvcResult result =
        post("preview", tokenBody(TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inviteeName").value("김운영"))
            .andExpect(jsonPath("$.roles[0]").value("SUPPORT"))
            .andExpect(jsonPath("$.roles[1]").value("BILLING"))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andReturn();
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("inviter")
        .doesNotContain("email")
        .doesNotContain("invitationId");
  }

  @Test
  void 초대_확인은_없는_토큰이면_404이고_형식이_틀리면_400이다() throws Exception {
    assertError(post("preview", tokenBody("unknown-token")), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(post("preview", "{}"), ErrorCode.INVALID_REQUEST);
    assertError(post("preview", "{\"token\":\"\"}"), ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 초대_확인은_만료_취소_수락_상태를_사유와_함께_409로_알린다() throws Exception {
    insertInvitation("expired-token", "만료", "PENDING", -1, OperatorRole.OPS);
    insertInvitation("cancelled-token", "취소", "CANCELLED", 24, OperatorRole.OPS);
    insertInvitation("accepted-token", "수락", "ACCEPTED", 24, OperatorRole.OPS);
    for (String[] pair :
        new String[][] {
          {"expired-token", "EXPIRED"},
          {"cancelled-token", "CANCELLED"},
          {"accepted-token", "ACCEPTED"}
        }) {
      post("preview", tokenBody(pair[0]))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("INVITATION_NOT_USABLE"))
          .andExpect(jsonPath("$.invitationState").value(pair[1]));
    }
  }

  @Test
  void 공개_엔드포인트는_같은_IP에서_분당_한도를_넘으면_429다() throws Exception {
    int tooMany = 0;
    for (int call = 0; call < 31; call++) {
      int statusCode =
          post("preview", tokenBody("unknown-" + call)).andReturn().getResponse().getStatus();
      if (statusCode == 429) {
        tooMany++;
      }
    }
    assertThat(tooMany).isEqualTo(1);
    assertError(post("kakao-start", tokenBody("another")), ErrorCode.RATE_LIMITED);
  }

  @Test
  void 카카오_시작은_인가_URL을_주고_없는_초대는_404_사용불가는_409다() throws Exception {
    insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.OPS);
    OpsStart start = startKakao(TOKEN);
    assertThat(start.cookie()).isNotNull();
    assertError(post("kakao-start", tokenBody("unknown-token")), ErrorCode.RESOURCE_NOT_FOUND);
    insertInvitation("cancelled-token", "취소", "CANCELLED", 24, OperatorRole.OPS);
    assertError(post("kakao-start", tokenBody("cancelled-token")), ErrorCode.INVITATION_NOT_USABLE);
    assertError(post("kakao-start", "{}"), ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 콜백은_식별값을_초대에_묶고_등록_화면으로_이동시킨다() throws Exception {
    long invitationId = insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.OPS);
    OpsStart start = startKakao(TOKEN);
    MvcResult result = callback(start, KAKAO_ID, start.state());
    assertThat(result.getResponse().getStatus()).isEqualTo(302);
    assertThat(result.getResponse().getHeader("Location")).endsWith("/ops/invitations/enroll");
    assertThat(opsCookieOf(result)).isNotNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT kakao_provider_key FROM operator_invitation WHERE id = ?",
                String.class,
                invitationId))
        .isEqualTo(KAKAO_ID);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT enrollment_expires_at > now() FROM operator_invitation WHERE id = ?",
                Boolean.class,
                invitationId))
        .isTrue();
  }

  @Test
  void 콜백은_state가_틀리면_400이고_이미_운영자_계정이_있는_식별값이면_409다() throws Exception {
    insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.OPS);
    OpsStart start = startKakao(TOKEN);
    assertThat(callback(start, KAKAO_ID, "wrong").getResponse().getStatus()).isEqualTo(400);

    createOperator(KAKAO_ID, "기존", OperatorRole.OPS);
    OpsStart second = startKakao(TOKEN);
    MvcResult conflict = callback(second, KAKAO_ID, second.state());
    assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
    assertThat(conflict.getResponse().getContentAsString()).contains("OPERATOR_ALREADY_EXISTS");
    assertThat(
            mockMvc
                .perform(get("/api/ops/auth/invitations/kakao/callback").param("code", "1"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void 콜백은_그_사이_취소된_초대면_409다() throws Exception {
    long invitationId = insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.OPS);
    OpsStart start = startKakao(TOKEN);
    var inviter = createOperator("canceller", "취소자", OperatorRole.SECURITY_ADMIN);
    jdbcTemplate.update(
        "UPDATE operator_invitation SET status = 'CANCELLED', cancelled_by_operator_id = ?,"
            + " cancelled_at = now() WHERE id = ?",
        inviter.id(),
        invitationId);
    MvcResult result = callback(start, KAKAO_ID, start.state());
    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    assertThat(result.getResponse().getContentAsString())
        .contains("INVITATION_NOT_USABLE")
        .contains("CANCELLED");
  }

  @Test
  void 비밀값_발급은_호출마다_새_값으로_대체하고_원문은_저장하지_않는다() throws Exception {
    long invitationId = insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.OPS);
    Cookie cookie = enrollmentCookie(TOKEN, KAKAO_ID);
    MvcResult first = post("mfa-enrollment", "", cookie).andExpect(status().isOk()).andReturn();
    String firstSecret = json(first).get("secret").asString();
    assertThat(json(first).get("otpauthUri").asString())
        .isEqualTo("otpauth://totp/SAIRO:ops?secret=" + firstSecret + "&issuer=SAIRO");
    assertThat(json(first).get("enrollmentExpiresAt").asString()).isNotBlank();
    String secondSecret = enroll(cookie);
    assertThat(secondSecret).isNotEqualTo(firstSecret).matches("[A-Z2-7]{32}");
    String stored =
        jdbcTemplate.queryForObject(
            "SELECT pending_totp_secret_encrypted FROM operator_invitation WHERE id = ?",
            String.class,
            invitationId);
    assertThat(stored).isNotEqualTo(secondSecret);
    assertThat(textCipher.decrypt(stored)).isEqualTo(secondSecret);
  }

  @Test
  void 비밀값_발급은_등록_쿠키가_없거나_만료되면_401이고_취소된_초대면_409다() throws Exception {
    long invitationId = insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.OPS);
    assertError(post("mfa-enrollment", ""), ErrorCode.UNAUTHENTICATED);
    Cookie cookie = enrollmentCookie(TOKEN, KAKAO_ID);

    setSessionAttribute(cookie, "OPS_ENROLL_UNTIL", Instant.now().minusSeconds(1));
    assertError(post("mfa-enrollment", "", cookie), ErrorCode.UNAUTHENTICATED);
    setSessionAttribute(cookie, "OPS_ENROLL_UNTIL", Instant.now().plusSeconds(600));

    var inviter = createOperator("canceller", "취소자", OperatorRole.SECURITY_ADMIN);
    jdbcTemplate.update(
        "UPDATE operator_invitation SET status = 'CANCELLED', cancelled_by_operator_id = ?,"
            + " cancelled_at = now() WHERE id = ?",
        inviter.id(),
        invitationId);
    assertError(post("mfa-enrollment", "", cookie), ErrorCode.INVITATION_NOT_USABLE);
  }

  @Test
  void 수락은_코드가_맞으면_계정과_역할을_만들고_감사_로그와_세션을_남긴다() throws Exception {
    long invitationId =
        insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.SUPPORT, OperatorRole.REVIEW);
    Cookie cookie = enrollmentCookie(TOKEN, KAKAO_ID);
    String secret = enroll(cookie);

    MvcResult result =
        post("accept", "{\"otpCode\":\"" + otpFor(secret, 0) + "\"}", cookie)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("김운영"))
            .andExpect(jsonPath("$.roles[0]").value("SUPPORT"))
            .andExpect(jsonPath("$.roles[1]").value("REVIEW"))
            .andExpect(jsonPath("$.acceptedAt").exists())
            .andReturn();
    long operatorId = json(result).get("operatorId").asLong();
    Cookie session = opsCookieOf(result);
    assertThat(session).isNotNull();
    mockMvc
        .perform(get("/api/ops/me").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operatorId").value(operatorId));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT kakao_provider_key FROM operator_account WHERE id = ?",
                String.class,
                operatorId))
        .isEqualTo(KAKAO_ID);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operator_role_assignment WHERE operator_account_id = ?",
                Long.class,
                operatorId))
        .isEqualTo(2L);
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT status, accepted_operator_id, pending_totp_secret_encrypted FROM"
                    + " operator_invitation WHERE id = ?",
                invitationId))
        .containsEntry("status", "ACCEPTED")
        .containsEntry("accepted_operator_id", operatorId)
        .containsEntry("pending_totp_secret_encrypted", null);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ops_audit_log WHERE operator_account_id = ? AND action_type"
                    + " IN ('OPERATOR_INVITATION_ACCEPTED', 'OPERATOR_ROLE_GRANTED')",
                Long.class,
                operatorId))
        .isEqualTo(3L);
    post("preview", tokenBody(TOKEN))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.invitationState").value("ACCEPTED"));
  }

  @Test
  void 수락은_코드가_틀리거나_발급_전이면_401이고_계정을_만들지_않는다() throws Exception {
    insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.OPS);
    Cookie cookie = enrollmentCookie(TOKEN, KAKAO_ID);
    assertError(post("accept", "{\"otpCode\":\"123456\"}", cookie), ErrorCode.MFA_CODE_INVALID);
    String secret = enroll(cookie);
    assertError(
        post("accept", "{\"otpCode\":\"" + wrongOtp(secret) + "\"}", cookie),
        ErrorCode.MFA_CODE_INVALID);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operator_account WHERE name = '김운영'", Long.class))
        .isZero();
  }

  @Test
  void 수락은_쿠키가_없으면_401이고_형식이_틀리면_400이다() throws Exception {
    insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.OPS);
    assertError(post("accept", "{\"otpCode\":\"123456\"}"), ErrorCode.UNAUTHENTICATED);
    Cookie cookie = enrollmentCookie(TOKEN, KAKAO_ID);
    assertError(post("accept", "{\"otpCode\":\"12\"}", cookie), ErrorCode.INVALID_REQUEST);
    assertError(post("accept", "{}", cookie), ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 수락_코드_시도는_초대당_분당_5회이고_넘으면_429다() throws Exception {
    insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.OPS);
    Cookie cookie = enrollmentCookie(TOKEN, KAKAO_ID);
    String secret = enroll(cookie);
    String wrong = wrongOtp(secret);
    for (int attempt = 1; attempt <= 5; attempt++) {
      assertError(
          post("accept", "{\"otpCode\":\"" + wrong + "\"}", cookie), ErrorCode.MFA_CODE_INVALID);
    }
    assertError(
        post("accept", "{\"otpCode\":\"" + otpFor(secret, 0) + "\"}", cookie),
        ErrorCode.RATE_LIMITED);
  }

  @Test
  void 수락은_그_사이_같은_식별값의_운영자가_생겼거나_초대가_취소되면_409다() throws Exception {
    long invitationId = insertInvitation(TOKEN, "김운영", "PENDING", 48, OperatorRole.OPS);
    Cookie cookie = enrollmentCookie(TOKEN, KAKAO_ID);
    String secret = enroll(cookie);
    createOperator(KAKAO_ID, "먼저 생김", OperatorRole.OPS);
    assertError(
        post("accept", "{\"otpCode\":\"" + otpFor(secret, 0) + "\"}", cookie),
        ErrorCode.OPERATOR_ALREADY_EXISTS);

    var inviter = createOperator("canceller", "취소자", OperatorRole.SECURITY_ADMIN);
    jdbcTemplate.update(
        "UPDATE operator_invitation SET status = 'CANCELLED', cancelled_by_operator_id = ?,"
            + " cancelled_at = now() WHERE id = ?",
        inviter.id(),
        invitationId);
    assertError(
        post("accept", "{\"otpCode\":\"" + otpFor(secret, 0) + "\"}", cookie),
        ErrorCode.INVITATION_NOT_USABLE);
  }
}
