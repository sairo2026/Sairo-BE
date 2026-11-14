package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.SignedTicketService;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.security.TicketType;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.Session;

@IntegrationTest
class MeIntegrationTest extends AuthIntegrationTestBase {

  private static final String EMAIL = "agent@example.com";

  @Autowired private SignedTicketService ticketService;

  private AppUser user;
  private Cookie session;

  @BeforeEach
  void createUser() throws Exception {
    user = saveEmailUser(EMAIL, "010-1234-5678");
    session = login(EMAIL, PASSWORD);
  }

  @Test
  void 내_계정_조회는_기본정보와_로그인수단과_알림_기본값을_돌려준다() throws Exception {
    mockMvc
        .perform(get("/api/me").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(user.getId()))
        .andExpect(jsonPath("$.displayName").value("김회원"))
        .andExpect(jsonPath("$.email").value(EMAIL))
        .andExpect(jsonPath("$.emailVerified").value(true))
        .andExpect(jsonPath("$.phone").value("010-1234-5678"))
        .andExpect(jsonPath("$.phoneVerified").value(true))
        .andExpect(jsonPath("$.kakaoLinked").value(false))
        .andExpect(jsonPath("$.loginMethods.length()").value(1))
        .andExpect(jsonPath("$.loginMethods[0]").value("EMAIL"))
        .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
        .andExpect(jsonPath("$.notificationPreferences.channels.VISIT_REMINDER").value(true))
        .andExpect(jsonPath("$.notificationPreferences.channels.MARKETING").value(false))
        .andExpect(jsonPath("$.notificationPreferences.channels.SECURITY").value(true))
        .andExpect(jsonPath("$.notificationPreferences.mandatoryTypes[0]").value("SECURITY"))
        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        .andExpect(jsonPath("$.termsAgreements.length()").value(0));
  }

  @Test
  void 카카오_전용_계정은_로그인수단이_KAKAO이고_이메일이_없다() throws Exception {
    AppUser kakaoUser =
        appUserRepository.saveAndFlush(
            com.sairo.be.support.fixture.AppUserBuilder.anAppUser().build());
    String sessionId =
        sessions.createMemberSession(
            new com.sairo.be.global.security.StaffPrincipal(
                kakaoUser.getId(), null, null, kakaoUser.getName(), null),
            Instant.now(),
            Instant.now());

    mockMvc
        .perform(get("/api/me").cookie(sessions.cookie(sessionId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").isEmpty())
        .andExpect(jsonPath("$.phone").isEmpty())
        .andExpect(jsonPath("$.phoneVerified").value(false))
        .andExpect(jsonPath("$.kakaoLinked").value(true))
        .andExpect(jsonPath("$.loginMethods[0]").value("KAKAO"));
  }

  @Test
  void 현재_세션_조회는_만료_시각과_재인증_상태를_돌려준다() throws Exception {
    mockMvc
        .perform(get("/api/me/session").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticatedAt").isNotEmpty())
        .andExpect(jsonPath("$.lastAccessedAt").isNotEmpty())
        .andExpect(jsonPath("$.idleExpiresAt").isNotEmpty())
        .andExpect(jsonPath("$.absoluteExpiresAt").isNotEmpty())
        .andExpect(jsonPath("$.stepUpValid").value(false))
        .andExpect(jsonPath("$.stepUpExpiresAt").isEmpty());
  }

  @Test
  void 세션_만료_시각은_마지막_사용_12시간_후와_최초_로그인_7일_후다() throws Exception {
    Instant issued = Instant.parse("2026-11-01T00:00:00Z");
    String sessionId =
        sessions.createMemberSession(
            new com.sairo.be.global.security.StaffPrincipal(
                user.getId(), null, null, user.getName(), null),
            Instant.now().minus(Duration.ofDays(1)),
            Instant.now());
    @SuppressWarnings({"rawtypes", "unchecked"})
    Session stored = (Session) sessionRepository.findById(sessionId);
    stored.setAttribute(
        com.sairo.be.global.security.AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE,
        Instant.now().minus(Duration.ofDays(1)));
    sessionRepository.save(stored);

    String body =
        mockMvc
            .perform(get("/api/me/session").cookie(sessions.cookie(sessionId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
    var authenticated = java.time.OffsetDateTime.parse(json.get("authenticatedAt").asString());
    var absolute = java.time.OffsetDateTime.parse(json.get("absoluteExpiresAt").asString());
    var last = java.time.OffsetDateTime.parse(json.get("lastAccessedAt").asString());
    var idle = java.time.OffsetDateTime.parse(json.get("idleExpiresAt").asString());
    assertThat(Duration.between(authenticated, absolute)).isEqualTo(Duration.ofDays(7));
    assertThat(Duration.between(last, idle)).isEqualTo(Duration.ofHours(12));
    assertThat(issued).isBefore(Instant.now());
  }

  @Test
  void 재인증이_살아_있으면_세션_조회가_유효_여부와_만료_시각을_돌려준다() throws Exception {
    String sessionId =
        sessions.createMemberSession(
            new com.sairo.be.global.security.StaffPrincipal(
                user.getId(), null, null, user.getName(), null),
            Instant.now(),
            Instant.now());
    @SuppressWarnings({"rawtypes", "unchecked"})
    Session stored = (Session) sessionRepository.findById(sessionId);
    stored.setAttribute(StepUpState.STEP_UP_UNTIL_ATTRIBUTE, Instant.now().plusSeconds(300));
    sessionRepository.save(stored);

    mockMvc
        .perform(get("/api/me/session").cookie(sessions.cookie(sessionId)))
        .andExpect(jsonPath("$.stepUpValid").value(true))
        .andExpect(jsonPath("$.stepUpExpiresAt").isNotEmpty());
  }

  @Test
  void 만료된_재인증은_유효하지_않다() throws Exception {
    String sessionId =
        sessions.createMemberSession(
            new com.sairo.be.global.security.StaffPrincipal(
                user.getId(), null, null, user.getName(), null),
            Instant.now(),
            Instant.now());
    @SuppressWarnings({"rawtypes", "unchecked"})
    Session stored = (Session) sessionRepository.findById(sessionId);
    stored.setAttribute(StepUpState.STEP_UP_UNTIL_ATTRIBUTE, Instant.now().minusSeconds(1));
    sessionRepository.save(stored);

    mockMvc
        .perform(get("/api/me/session").cookie(sessions.cookie(sessionId)))
        .andExpect(jsonPath("$.stepUpValid").value(false))
        .andExpect(jsonPath("$.stepUpExpiresAt").isEmpty());
  }

  @Test
  void 로그인_이력은_최신순이고_환경은_요약으로_돌려준다() throws Exception {
    jdbcTemplate.update("DELETE FROM login_history");
    for (int i = 0; i < 3; i++) {
      jdbcTemplate.update(
          "INSERT INTO login_history (user_id, auth_method, ip_address, user_agent, created_at)"
              + " VALUES (?, 'EMAIL', '1.2.3.4', ?, now() - (? || ' hours')::interval)",
          user.getId(),
          "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36",
          String.valueOf(i));
    }

    mockMvc
        .perform(get("/api/me/login-history").cookie(session).param("size", "25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.items[0].authMethod").value("EMAIL"))
        .andExpect(jsonPath("$.items[0].ipAddress").value("1.2.3.4"))
        .andExpect(jsonPath("$.items[0].userAgent").value("Chrome on macOS"))
        .andExpect(jsonPath("$.items[0].loginHistoryId").isNumber());
    String firstId =
        mockMvc
            .perform(get("/api/me/login-history").cookie(session))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(firstId).contains("createdAt");
  }

  @Test
  void 로그인_이력은_본인_기록만_페이지로_나눠_돌려준다() throws Exception {
    AppUser other = saveEmailUser("other@example.com", null);
    jdbcTemplate.update("DELETE FROM login_history");
    for (int i = 0; i < 30; i++) {
      jdbcTemplate.update(
          "INSERT INTO login_history (user_id, auth_method, ip_address, user_agent)"
              + " VALUES (?, 'EMAIL', '1.2.3.4', 'x')",
          user.getId());
    }
    jdbcTemplate.update(
        "INSERT INTO login_history (user_id, auth_method, ip_address, user_agent)"
            + " VALUES (?, 'KAKAO', '5.6.7.8', 'x')",
        other.getId());

    mockMvc
        .perform(get("/api/me/login-history").cookie(session).param("page", "1"))
        .andExpect(jsonPath("$.items.length()").value(5))
        .andExpect(jsonPath("$.totalElements").value(30))
        .andExpect(jsonPath("$.page").value(1));
  }

  @Test
  void 빈_로그인_이력은_빈_목록이다() throws Exception {
    jdbcTemplate.update("DELETE FROM login_history");

    mockMvc
        .perform(get("/api/me/login-history").cookie(session))
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void 허용되지_않는_페이지_크기는_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(get("/api/me/login-history").cookie(session).param("size", "30")),
        "size",
        "NOT_ALLOWED");
  }

  @Test
  void 로그인이_필요한_엔드포인트는_세션이_없으면_401이다() throws Exception {
    for (String url :
        List.of(
            "/api/me",
            "/api/me/session",
            "/api/me/login-history",
            "/api/me/terms-reconsent",
            "/api/auth/context")) {
      assertError(mockMvc.perform(get(url)), ErrorCode.UNAUTHENTICATED);
    }
  }

  @Test
  void 계정_복구_안내는_티켓이_하나도_없거나_둘_다_있으면_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(get("/api/auth/recovery-options")), "verifiedEmailTicket", "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(
            get("/api/auth/recovery-options")
                .param("verifiedEmailTicket", "a")
                .param("verifiedPhoneTicket", "b")),
        "verifiedEmailTicket",
        "INVALID_VALUE");
  }

  @Test
  void 계정_복구_안내는_휴대폰_티켓으로_기존_계정의_수단을_안내하고_티켓을_소진하지_않는다() throws Exception {
    String ticket = flow.sendAndVerifyPhone("010-1234-5678");

    mockMvc
        .perform(get("/api/auth/recovery-options").param("verifiedPhoneTicket", ticket))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.existingAccountFound").value(true))
        .andExpect(jsonPath("$.availableLoginMethods[0]").value("EMAIL"))
        .andExpect(jsonPath("$.passwordResetAvailable").value(true));
    mockMvc
        .perform(get("/api/auth/recovery-options").param("verifiedPhoneTicket", ticket))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.existingAccountFound").value(true));
  }

  @Test
  void 계정이_없는_번호의_복구_안내는_존재하지_않음을_돌려준다() throws Exception {
    String ticket = flow.sendAndVerifyPhone("010-9999-8888");

    mockMvc
        .perform(get("/api/auth/recovery-options").param("verifiedPhoneTicket", ticket))
        .andExpect(jsonPath("$.existingAccountFound").value(false))
        .andExpect(jsonPath("$.availableLoginMethods.length()").value(0))
        .andExpect(jsonPath("$.passwordResetAvailable").value(false));
  }

  @Test
  void 이메일_티켓으로도_복구_안내를_조회한다() throws Exception {
    String ticket =
        ticketService
            .issue(TicketType.EMAIL, EMAIL, "SIGNUP", null, Duration.ofMinutes(10))
            .token();

    mockMvc
        .perform(get("/api/auth/recovery-options").param("verifiedEmailTicket", ticket))
        .andExpect(jsonPath("$.existingAccountFound").value(true))
        .andExpect(jsonPath("$.availableLoginMethods[0]").value("EMAIL"));
  }

  @Test
  void 무효이거나_만료된_복구_티켓은_422다() throws Exception {
    assertError(
        mockMvc.perform(get("/api/auth/recovery-options").param("verifiedPhoneTicket", "forged")),
        ErrorCode.TOKEN_INVALID);
    String expired =
        ticketService
            .issue(TicketType.PHONE, "010-1234-5678", "SIGNUP", null, Duration.ofSeconds(-1))
            .token();
    assertError(
        mockMvc.perform(get("/api/auth/recovery-options").param("verifiedPhoneTicket", expired)),
        ErrorCode.TOKEN_EXPIRED);
  }

  @Test
  void 변경_목적_티켓은_복구_안내에_쓸_수_없다() throws Exception {
    String ticket =
        ticketService
            .issue(
                TicketType.PHONE, "010-1234-5678", "CHANGE", user.getId(), Duration.ofMinutes(10))
            .token();

    assertError(
        mockMvc.perform(get("/api/auth/recovery-options").param("verifiedPhoneTicket", ticket)),
        ErrorCode.TOKEN_INVALID);
  }
}
