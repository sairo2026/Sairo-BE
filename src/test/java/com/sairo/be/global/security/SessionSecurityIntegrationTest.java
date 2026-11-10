package com.sairo.be.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.SessionTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SessionSecurityIntegrationTest {

  private static final StaffPrincipal PRINCIPAL =
      new StaffPrincipal(9101L, 9201L, 9001L, "박직원", StaffRole.AGENT);

  @Autowired private MockMvc mockMvc;
  @Autowired private SessionRepository<?> sessionRepository;
  @Autowired private StringRedisTemplate redisTemplate;

  private SessionTestSupport sessions;

  @BeforeEach
  void setUp() {
    sessions = new SessionTestSupport(sessionRepository);
  }

  @Test
  void 새_세션의_기본_미활동_만료는_12시간이다() {
    var session = sessionRepository.createSession();

    assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofHours(12));
  }

  @Test
  void 미활동_12시간_전에는_세션을_복원하고_요청시_접속시각을_갱신한다() throws Exception {
    Instant lastAccess = Instant.now().minus(Duration.ofHours(12)).plusSeconds(60);
    String sessionId =
        sessions.createMemberSession(
            PRINCIPAL, Instant.now().minus(Duration.ofDays(3)), lastAccess);

    mockMvc
        .perform(get("/api/probe/staff").cookie(sessions.cookie(sessionId)))
        .andExpect(status().isOk())
        .andExpect(content().string("9001"));

    var refreshed = sessionRepository.findById(sessionId);
    assertThat(refreshed.getLastAccessedTime()).isAfter(lastAccess);
  }

  @Test
  void 미활동_12시간에_도달하면_401을_반환하고_세션을_폐기한다() throws Exception {
    String sessionId =
        sessions.createMemberSession(
            PRINCIPAL,
            Instant.now().minus(Duration.ofDays(3)),
            Instant.now().minus(Duration.ofHours(12)));

    mockMvc
        .perform(get("/api/probe/staff").cookie(sessions.cookie(sessionId)))
        .andExpect(status().isUnauthorized());

    assertThat(sessions.exists(sessionId)).isFalse();
  }

  @Test
  void 최근_활동했어도_발급후_7일이면_401을_반환하고_세션을_폐기한다() throws Exception {
    String sessionId =
        sessions.createMemberSession(
            PRINCIPAL, Instant.now().minus(Duration.ofDays(7)), Instant.now());

    mockMvc
        .perform(get("/api/probe/staff").cookie(sessions.cookie(sessionId)))
        .andExpect(status().isUnauthorized());

    assertThat(sessions.exists(sessionId)).isFalse();
  }

  @Test
  void 발급시각이_없는_인증세션은_401을_반환하고_폐기한다() throws Exception {
    String sessionId = sessions.createMemberSession(PRINCIPAL, null, Instant.now());

    mockMvc
        .perform(get("/api/probe/staff").cookie(sessions.cookie(sessionId)))
        .andExpect(status().isUnauthorized());

    assertThat(sessions.exists(sessionId)).isFalse();
  }

  @Test
  void 사무소_입력값이_달라도_각_세션의_인증주체를_유지한다() throws Exception {
    String firstSessionId = sessions.createMemberSession(PRINCIPAL, Instant.now(), Instant.now());
    String secondSessionId =
        sessions.createMemberSession(
            new StaffPrincipal(9102L, 9202L, 9002L, "김직원", StaffRole.OWNER),
            Instant.now(),
            Instant.now());

    mockMvc
        .perform(
            get("/api/probe/staff")
                .param("officeId", "9002")
                .header("officeId", "9002")
                .cookie(sessions.cookie(firstSessionId)))
        .andExpect(status().isOk())
        .andExpect(content().string("9001"));
    mockMvc
        .perform(
            get("/api/probe/staff")
                .param("officeId", "9001")
                .header("officeId", "9001")
                .cookie(sessions.cookie(secondSessionId)))
        .andExpect(status().isOk())
        .andExpect(content().string("9002"));
  }

  @Test
  void 인증되지_않은_요청의_401_응답은_UTF_8로_한글_메시지를_전달한다() throws Exception {
    mockMvc
        .perform(get("/api/probe/staff"))
        .andExpect(status().isUnauthorized())
        .andExpect(
            content()
                .contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void 인증세션이_있어도_CSRF_토큰없는_변경요청은_403을_반환한다() throws Exception {
    String sessionId = sessions.createMemberSession(PRINCIPAL, Instant.now(), Instant.now());

    mockMvc
        .perform(post("/api/probe/change").cookie(sessions.cookie(sessionId)))
        .andExpect(status().isForbidden());
  }

  @Test
  void 브라우저가_XSRF_TOKEN_쿠키값을_그대로_헤더로_보내면_변경요청이_성공한다() throws Exception {
    String sessionId = sessions.createMemberSession(PRINCIPAL, Instant.now(), Instant.now());

    var firstAttempt =
        mockMvc
            .perform(get("/api/probe/authenticated").cookie(sessions.cookie(sessionId)))
            .andReturn();
    String csrfToken = extractCookieValue(firstAttempt.getResponse(), "XSRF-TOKEN");
    assertThat(csrfToken).isNotNull();

    mockMvc
        .perform(
            post("/api/probe/change")
                .cookie(sessions.cookie(sessionId), new Cookie("XSRF-TOKEN", csrfToken))
                .header("X-XSRF-TOKEN", csrfToken))
        .andExpect(status().isOk());
  }

  @Test
  void 운영자_세션은_미활동_30분과_절대_8시간으로_만료된다() throws Exception {
    String idleExpired =
        sessions.createSession(
            PRINCIPAL,
            SessionKind.OPERATOR,
            Instant.now().minus(Duration.ofHours(1)),
            Instant.now().minus(Duration.ofMinutes(30)));
    String absoluteExpired =
        sessions.createSession(
            PRINCIPAL,
            SessionKind.OPERATOR,
            Instant.now().minus(Duration.ofHours(8)),
            Instant.now());
    String alive =
        sessions.createSession(
            PRINCIPAL,
            SessionKind.OPERATOR,
            Instant.now().minus(Duration.ofHours(7)),
            Instant.now().minus(Duration.ofMinutes(29)));

    mockMvc
        .perform(get("/api/probe/authenticated").cookie(sessions.cookie(idleExpired)))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/probe/authenticated").cookie(sessions.cookie(absoluteExpired)))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/probe/authenticated").cookie(sessions.cookie(alive)))
        .andExpect(status().isOk());
  }

  @Test
  void 승인자_세션은_미활동_60분과_절대_12시간으로_만료된다() throws Exception {
    String idleExpired =
        sessions.createSession(
            PRINCIPAL,
            SessionKind.APPROVER,
            Instant.now().minus(Duration.ofHours(2)),
            Instant.now().minus(Duration.ofMinutes(60)));
    String absoluteExpired =
        sessions.createSession(
            PRINCIPAL,
            SessionKind.APPROVER,
            Instant.now().minus(Duration.ofHours(12)),
            Instant.now());
    String alive =
        sessions.createSession(
            PRINCIPAL,
            SessionKind.APPROVER,
            Instant.now().minus(Duration.ofHours(11)),
            Instant.now().minus(Duration.ofMinutes(59)));

    mockMvc
        .perform(get("/api/probe/authenticated").cookie(sessions.cookie(idleExpired)))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/probe/authenticated").cookie(sessions.cookie(absoluteExpired)))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/probe/authenticated").cookie(sessions.cookie(alive)))
        .andExpect(status().isOk());
  }

  @Test
  void 세션은_인증주체_이름으로_색인되어_사용자별로_일괄_폐기할_수_있다() {
    String sessionId = sessions.createMemberSession(PRINCIPAL, Instant.now(), Instant.now());

    var indexed =
        ((org.springframework.session.FindByIndexNameSessionRepository<?>) sessionRepository)
            .findByPrincipalName("9101");

    assertThat(indexed).containsKey(sessionId);
    assertThat(redisTemplate.keys("sairo:session:*")).isNotEmpty();
  }

  private String extractCookieValue(
      org.springframework.mock.web.MockHttpServletResponse response, String name) {
    String prefix = name + "=";
    for (String header : response.getHeaders("Set-Cookie")) {
      if (header.startsWith(prefix)) {
        String value = header.substring(prefix.length());
        int semicolon = value.indexOf(';');
        return semicolon >= 0 ? value.substring(0, semicolon) : value;
      }
    }
    return null;
  }
}
