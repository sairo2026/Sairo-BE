package com.sairo.be.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;

public class PathAwareSessionIdResolver implements HttpSessionIdResolver {

  public static final String OPERATOR_PATH_PREFIX = "/api/ops/";
  public static final String OPERATOR_ARCHIVE_GRANT_PATH = "/api/archive-grants";

  private final CookieHttpSessionIdResolver memberResolver = new CookieHttpSessionIdResolver();
  private final CookieHttpSessionIdResolver operatorResolver = new CookieHttpSessionIdResolver();

  public PathAwareSessionIdResolver(
      CookieSerializer memberSerializer, CookieSerializer operatorSerializer) {
    memberResolver.setCookieSerializer(memberSerializer);
    operatorResolver.setCookieSerializer(operatorSerializer);
  }

  @Override
  public List<String> resolveSessionIds(HttpServletRequest request) {
    return pick(request).resolveSessionIds(request);
  }

  @Override
  public void setSessionId(
      HttpServletRequest request, HttpServletResponse response, String sessionId) {
    pick(request).setSessionId(request, response, sessionId);
  }

  @Override
  public void expireSession(HttpServletRequest request, HttpServletResponse response) {
    pick(request).expireSession(request, response);
  }

  private CookieHttpSessionIdResolver pick(HttpServletRequest request) {
    return isOperatorRequest(request) ? operatorResolver : memberResolver;
  }

  private static boolean isOperatorRequest(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri.startsWith(OPERATOR_PATH_PREFIX)
        || ("POST".equals(request.getMethod()) && OPERATOR_ARCHIVE_GRANT_PATH.equals(uri));
  }
}
