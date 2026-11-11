package com.sairo.be.global.security.access;

import com.sairo.be.global.support.RateLimitRule;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.web.ClientRequestInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 승인자 세션 경로의 요청 제한이다. 세션 식별자와 IP는 요청 제한 전용 HMAC 변환값으로만 센다. */
@Profile("!migrate")
@Component
public class ApproverPortalRateLimitInterceptor implements HandlerInterceptor {

  public static final RateLimitRule SESSION =
      RateLimitRule.of("approver-portal-session", 60, Duration.ofSeconds(60));
  public static final RateLimitRule IP =
      RateLimitRule.of("approver-portal-ip", 120, Duration.ofSeconds(60));

  private final RequestRateLimiter rateLimiter;

  public ApproverPortalRateLimitInterceptor(RequestRateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    rateLimiter.hit(IP, ClientRequestInfo.from(request).ipAddress());
    HttpSession session = request.getSession(false);
    if (session != null) {
      rateLimiter.hit(SESSION, session.getId());
    }
    return true;
  }
}
