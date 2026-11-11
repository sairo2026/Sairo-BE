package com.sairo.be.global.security;

import com.sairo.be.global.security.access.PrincipalType;
import com.sairo.be.global.security.access.TypedAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class GuestLinkAuthenticationFilter extends OncePerRequestFilter {

  private static final Pattern LINK_PATH =
      Pattern.compile(
          "^/api/public/(?:(?:customer-visit-links|approver-property-links)/([^/]{1,512})(/.*)?"
              + "|privacy-intakes)$");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !LINK_PATH.matcher(request.getRequestURI()).matches();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new GuestLinkAuthentication());
    SecurityContextHolder.setContext(context);
    filterChain.doFilter(request, response);
  }

  static class GuestLinkAuthentication extends AbstractAuthenticationToken
      implements TypedAuthentication {

    GuestLinkAuthentication() {
      super(List.of());
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      return null;
    }

    @Override
    public Object getPrincipal() {
      return "guest-link";
    }

    @Override
    public PrincipalType principalType() {
      return PrincipalType.GUEST_LINK;
    }
  }
}
