package com.sairo.be.global.security.access;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.SessionKind;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.SessionTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@IntegrationTest
class AccessInterceptorIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private SessionRepository<?> sessionRepository;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;

  @Autowired private AccessPolicy accessPolicy;

  private SessionTestSupport sessions;

  @BeforeEach
  void setUp() {
    sessions = new SessionTestSupport(sessionRepository);
  }

  @Test
  void 공개_권한은_로그인_없이_접근한다() throws Exception {
    mockMvc
        .perform(get("/api/probe/public"))
        .andExpect(status().isOk())
        .andExpect(content().string("public"));
  }

  @Test
  void 로그인하지_않으면_회원_권한_값에서_401이다() throws Exception {
    assertError(mockMvc.perform(get("/api/probe/authenticated")), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(get("/api/probe/staff")), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(get("/api/probe/owner")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 역할에_따라_대표_전용_경로를_막는다() throws Exception {
    String agent = login(StaffRole.AGENT);
    String deputy = login(StaffRole.DEPUTY);
    String owner = login(StaffRole.OWNER);

    mockMvc
        .perform(get("/api/probe/owner").cookie(sessions.cookie(owner)))
        .andExpect(status().isOk());
    assertError(
        mockMvc.perform(get("/api/probe/owner").cookie(sessions.cookie(agent))),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(get("/api/probe/agent-or-owner").cookie(sessions.cookie(deputy))),
        ErrorCode.FORBIDDEN);
    mockMvc
        .perform(get("/api/probe/agent-or-owner").cookie(sessions.cookie(agent)))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/probe/staff").cookie(sessions.cookie(deputy)))
        .andExpect(status().isOk());
  }

  @Test
  void 사무소_구성원_세션은_운영자_경로에_접근하지_못한다() throws Exception {
    String member = login(StaffRole.OWNER);

    assertError(
        mockMvc.perform(get("/api/probe/operator").cookie(sessions.cookie(member))),
        ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get("/api/probe/operator")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 링크_권한_값은_링크_검증_주체가_없으면_404이고_웹훅은_401이다() throws Exception {
    assertError(mockMvc.perform(get("/api/probe/guest-link")), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(mockMvc.perform(get("/api/probe/webhook")), ErrorCode.WEBHOOK_AUTH_FAILED);
  }

  @Test
  void 권한_값을_선언하지_않은_API는_기본적으로_막는다() throws Exception {
    assertError(mockMvc.perform(get("/api/probe/unannotated")), ErrorCode.FORBIDDEN);
    String owner = login(StaffRole.OWNER);
    assertError(
        mockMvc.perform(get("/api/probe/unannotated").cookie(sessions.cookie(owner))),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 존재하지_않는_API_경로는_문서_형식의_404를_돌려준다() throws Exception {
    assertError(mockMvc.perform(get("/api/does-not-exist")), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 응답에_추적ID_헤더가_붙고_오류_본문의_추적ID와_같다() throws Exception {
    var result = mockMvc.perform(get("/api/probe/staff")).andReturn();

    String header = result.getResponse().getHeader("X-Trace-Id");
    assertThat(header).isNotBlank();
    assertThat(result.getResponse().getContentAsString()).contains(header);
  }

  @Test
  void 모든_API_핸들러는_권한_값을_선언했다_테스트용_무선언_경로만_예외다() {
    for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
      RequestMappingInfo info = entry.getKey();
      HandlerMethod method = entry.getValue();
      boolean apiPath =
          info.getPathPatternsCondition() != null
              && info.getPathPatternsCondition().getPatterns().stream()
                  .anyMatch(p -> p.getPatternString().startsWith("/api/"));
      if (!apiPath
          || info.getPathPatternsCondition().getPatterns().stream()
              .anyMatch(p -> p.getPatternString().equals("/api/probe/unannotated"))) {
        continue;
      }
      assertThat(AccessInterceptor.findAnnotation(method))
          .as("권한 값 미선언: %s", method.getShortLogMessage())
          .isNotNull();
    }
  }

  @Test
  void 기동한_정책은_일곱_단계를_순서대로_가진다() {
    assertThat(accessPolicy.order()).containsExactly(AccessStageType.values());
  }

  private String login(StaffRole role) {
    return sessions.createSession(
        new StaffPrincipal(9101L, 9201L, 9001L, "박직원", role),
        SessionKind.MEMBER,
        Instant.now(),
        Instant.now());
  }
}
