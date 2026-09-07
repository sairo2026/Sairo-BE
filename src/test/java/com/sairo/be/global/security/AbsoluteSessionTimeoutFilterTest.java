package com.sairo.be.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AbsoluteSessionTimeoutFilterTest {

  private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
  private final AbsoluteSessionTimeoutFilter filter =
      new AbsoluteSessionTimeoutFilter(Clock.fixed(NOW, ZoneOffset.UTC));

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void 세션이_없으면_그대로_통과한다() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(request.getSession(false)).isNull();
    verify(chain).doFilter(request, response);
  }

  @Test
  void 발급후_90일_직전에는_인증세션을_유지한다() throws Exception {
    MockHttpSession session = authenticatedSession(NOW.minus(Duration.ofDays(90)).plusNanos(1));

    filterSession(session);

    assertThat(session.isInvalid()).isFalse();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1})
  void 발급후_90일_도달시_세션과_인증을_폐기한다(long elapsedNanos) throws Exception {
    MockHttpSession session =
        authenticatedSession(NOW.minus(Duration.ofDays(90)).minusNanos(elapsedNanos));

    filterSession(session);

    assertThat(session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"invalid", "2026-09-07T00:00:00Z"})
  void 인증세션의_발급시각이_없거나_타입이_다르면_폐기한다(String issuedAt) throws Exception {
    MockHttpSession session = authenticatedSession(issuedAt);

    filterSession(session);

    assertThat(session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void 인증세션의_발급시각이_미래면_폐기한다() throws Exception {
    MockHttpSession session = authenticatedSession(NOW.plusNanos(1));

    filterSession(session);

    assertThat(session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void 인증전_OAuth_세션은_발급시각이_없어도_유지한다() throws Exception {
    MockHttpSession session = new MockHttpSession();

    filterSession(session);

    assertThat(session.isInvalid()).isFalse();
  }

  @Test
  void 익명인증의_OAuth_세션은_발급시각이_없어도_유지한다() throws Exception {
    MockHttpSession session = new MockHttpSession();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "test", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

    filterSession(session);

    assertThat(session.isInvalid()).isFalse();
  }

  private MockHttpSession authenticatedSession(Object issuedAt) {
    MockHttpSession session = new MockHttpSession();
    if (issuedAt != null) {
      session.setAttribute(AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE, issuedAt);
    }
    SecurityContextHolder.getContext()
        .setAuthentication(new StaffAuthentication(new StaffPrincipal(1L, 2L, 3L, "박직원")));
    return session;
  }

  private void filterSession(MockHttpSession session) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
  }
}
