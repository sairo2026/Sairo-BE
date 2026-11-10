package com.sairo.be.global.security;

import com.sairo.be.global.security.access.PrincipalType;
import com.sairo.be.global.security.access.TypedAuthentication;
import com.sairo.be.global.support.HmacSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class ExternalWebhookAuthenticationFilter extends OncePerRequestFilter {

  public static final String KAKAO_UNLINK_PATH = "/api/webhooks/kakao/unlink";
  private static final String KAKAO_SCHEME = "KakaoAK ";

  private final Supplier<String> kakaoAdminKey;

  public ExternalWebhookAuthenticationFilter(Supplier<String> kakaoAdminKey) {
    this.kakaoAdminKey = kakaoAdminKey;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !KAKAO_UNLINK_PATH.equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String expected = kakaoAdminKey.get();
    String header = request.getHeader("Authorization");
    if (expected != null
        && !expected.isBlank()
        && header != null
        && header.startsWith(KAKAO_SCHEME)
        && HmacSupport.constantTimeEquals(header.substring(KAKAO_SCHEME.length()), expected)) {
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(new ExternalWebhookAuthentication());
      SecurityContextHolder.setContext(context);
    }
    filterChain.doFilter(request, response);
  }

  static class ExternalWebhookAuthentication extends AbstractAuthenticationToken
      implements TypedAuthentication {

    ExternalWebhookAuthentication() {
      super(List.of());
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      return null;
    }

    @Override
    public Object getPrincipal() {
      return "kakao-webhook";
    }

    @Override
    public PrincipalType principalType() {
      return PrincipalType.EXTERNAL_WEBHOOK;
    }
  }
}
