package com.sairo.be.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Import({TestcontainersConfiguration.class, SessionSecurityIntegrationTest.SessionProbe.class})
@SpringBootTest
@AutoConfigureMockMvc
class SessionSecurityIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcIndexedSessionRepository sessions;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void JDBC_인증세션은_14일_미활동_만료로_설정된다() {
    var session = sessions.createSession();

    assertThat(((Session) session).getMaxInactiveInterval()).isEqualTo(Duration.ofDays(14));
  }

  @Test
  void 미활동_14일_전에는_세션을_복원하고_요청시_접속시각을_갱신한다() throws Exception {
    Instant lastAccess = Instant.now().minus(Duration.ofDays(14)).plusSeconds(60);
    String sessionId = createSession(Instant.now().minus(Duration.ofDays(20)), lastAccess, 9001L);

    mockMvc
        .perform(get("/api/session-probe").cookie(cookie(sessionId)))
        .andExpect(status().isOk())
        .andExpect(content().string("9001"));

    var refreshed = sessions.findById(sessionId);
    assertThat(((Session) refreshed).getLastAccessedTime()).isAfter(lastAccess);
  }

  @Test
  void 미활동_14일에_도달하면_401을_반환하고_JDBC_세션을_폐기한다() throws Exception {
    String sessionId =
        createSession(
            Instant.now().minus(Duration.ofDays(20)),
            Instant.now().minus(Duration.ofDays(14)),
            9001L);

    mockMvc
        .perform(get("/api/session-probe").cookie(cookie(sessionId)))
        .andExpect(status().isUnauthorized());

    assertSessionDeleted(sessionId);
  }

  @Test
  void 최근_활동했어도_발급후_90일이면_401을_반환하고_JDBC_세션을_폐기한다() throws Exception {
    String sessionId =
        createSession(Instant.now().minus(Duration.ofDays(90)), Instant.now(), 9001L);

    mockMvc
        .perform(get("/api/session-probe").cookie(cookie(sessionId)))
        .andExpect(status().isUnauthorized());

    assertSessionDeleted(sessionId);
  }

  @Test
  void 발급시각이_없는_JDBC_인증세션은_401을_반환하고_폐기한다() throws Exception {
    String sessionId = createSession(null, Instant.now(), 9001L);

    mockMvc
        .perform(get("/api/session-probe").cookie(cookie(sessionId)))
        .andExpect(status().isUnauthorized());

    assertSessionDeleted(sessionId);
  }

  @Test
  void 사무소_입력값이_달라도_각_JDBC_세션의_인증주체를_유지한다() throws Exception {
    String firstSessionId = createSession(Instant.now(), Instant.now(), 9001L);
    String secondSessionId = createSession(Instant.now(), Instant.now(), 9002L);

    mockMvc
        .perform(
            get("/api/session-probe")
                .param("officeId", "9002")
                .header("officeId", "9002")
                .cookie(cookie(firstSessionId)))
        .andExpect(status().isOk())
        .andExpect(content().string("9001"));
    mockMvc
        .perform(
            get("/api/session-probe")
                .param("officeId", "9001")
                .header("officeId", "9001")
                .cookie(cookie(secondSessionId)))
        .andExpect(status().isOk())
        .andExpect(content().string("9002"));
  }

  @Test
  void 인증되지_않은_요청의_401_응답은_UTF_8로_한글_메시지를_전달한다() throws Exception {
    mockMvc
        .perform(get("/api/session-probe"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
  }

  @Test
  void 인증세션이_있어도_CSRF_토큰없는_변경요청은_403을_반환한다() throws Exception {
    String sessionId = createSession(Instant.now(), Instant.now(), 9001L);

    mockMvc
        .perform(post("/api/session-probe").cookie(cookie(sessionId)))
        .andExpect(status().isForbidden());
  }

  private String createSession(Instant issuedAt, Instant lastAccess, Long officeId) {
    var session = sessions.createSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new StaffAuthentication(new StaffPrincipal(9101L, 9201L, officeId, "박직원")));
    ((Session) session)
        .setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    if (issuedAt != null) {
      ((Session) session).setAttribute(AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE, issuedAt);
    }
    ((Session) session).setLastAccessedTime(lastAccess);
    sessions.save(session);
    return ((Session) session).getId();
  }

  private Cookie cookie(String sessionId) {
    return new Cookie(
        "__Host-sairo_session",
        Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
  }

  private void assertSessionDeleted(String sessionId) {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM spring_session WHERE session_id = ?", Long.class, sessionId))
        .isZero();
  }

  @RestController
  static class SessionProbe {

    @GetMapping("/api/session-probe")
    Long officeId(@AuthenticationPrincipal StaffPrincipal principal) {
      return principal.officeId();
    }

    @PostMapping("/api/session-probe")
    void change() {}
  }
}
