package com.sairo.be.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class AbsoluteSessionTimeoutFilterTest {

  private final AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void 세션이_없으면_그대로_통과한다() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getSession(false)).thenReturn(null);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
  }

  @Test
  void 발급후_90일이_지나지_않았으면_세션을_유지한다() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    HttpSession session = mock(HttpSession.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getSession(false)).thenReturn(session);
    when(session.getAttribute(AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE))
        .thenReturn(Instant.now().minusSeconds(60));

    filter.doFilter(request, response, chain);

    verify(session, never()).invalidate();
    verify(chain).doFilter(request, response);
  }

  @Test
  void 발급후_90일이_지나면_세션을_무효화하고_보안컨텍스트를_비운다() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    HttpSession session = mock(HttpSession.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getSession(false)).thenReturn(session);
    when(session.getAttribute(AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE))
        .thenReturn(Instant.now().minus(java.time.Duration.ofDays(91)));
    SecurityContextHolder.getContext()
        .setAuthentication(new StaffAuthentication(new StaffPrincipal(1L, 2L, 3L, "홍길동")));

    filter.doFilter(request, response, chain);

    verify(session).invalidate();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(chain).doFilter(request, response);
  }

  @Test
  void 발급시각_속성이_없으면_세션을_유지한다() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    HttpSession session = mock(HttpSession.class);
    FilterChain chain = mock(FilterChain.class);
    when(request.getSession(false)).thenReturn(session);
    when(session.getAttribute(AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE)).thenReturn(null);

    filter.doFilter(request, response, chain);

    verify(session, never()).invalidate();
    verify(chain).doFilter(request, response);
  }
}
