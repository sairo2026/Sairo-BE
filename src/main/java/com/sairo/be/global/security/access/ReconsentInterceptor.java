package com.sairo.be.global.security.access;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.ReconsentStatusProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Profile("!migrate")
@Component
public class ReconsentInterceptor implements HandlerInterceptor {

  private static final PathPatternParser PARSER = new PathPatternParser();

  private static final List<Exemption> EXEMPTIONS =
      List.of(
          exempt(HttpMethod.GET, "/api/auth/context"),
          exempt(HttpMethod.GET, "/api/me/session"),
          exempt(HttpMethod.GET, "/api/me/terms-reconsent"),
          exempt(HttpMethod.POST, "/api/me/terms-agreements"),
          exempt(HttpMethod.GET, "/api/me/terms-agreements"),
          exempt(HttpMethod.POST, "/api/auth/logout"),
          exempt(HttpMethod.GET, "/api/me/withdrawal-preview"),
          exempt(HttpMethod.DELETE, "/api/me"),
          exempt(HttpMethod.POST, "/api/privacy-requests"),
          exempt(HttpMethod.GET, "/api/privacy-requests/me"),
          exempt(HttpMethod.GET, "/api/privacy-requests/{requestId}"),
          exempt(HttpMethod.POST, "/api/support-inquiries"),
          exempt(HttpMethod.GET, "/api/support-inquiries/me"),
          exempt(HttpMethod.GET, "/api/support-inquiries/{inquiryId}"),
          exempt(HttpMethod.POST, "/api/support-inquiries/{inquiryId}/replies"),
          exempt(HttpMethod.POST, "/api/support-inquiries/{inquiryId}/attachments"),
          exempt(HttpMethod.GET, "/api/support-inquiries/{inquiryId}/attachments/{attachmentId}"));

  private final ReconsentStatusProvider reconsentStatusProvider;

  public ReconsentInterceptor(ReconsentStatusProvider reconsentStatusProvider) {
    this.reconsentStatusProvider = reconsentStatusProvider;
  }

  public static int exemptionCount() {
    return EXEMPTIONS.size();
  }

  public static boolean isExempt(String method, String path) {
    return EXEMPTIONS.stream().anyMatch(exemption -> exemption.matches(method, path));
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    AccessSubject subject =
        AccessSubject.from(SecurityContextHolder.getContext().getAuthentication());
    if (subject.type() != PrincipalType.MEMBER || subject.userId() == null) {
      return true;
    }
    if (isExempt(request.getMethod(), request.getRequestURI())) {
      return true;
    }
    if (reconsentStatusProvider.isReconsentRequired(subject.userId())) {
      throw new BusinessException(ErrorCode.RECONSENT_REQUIRED);
    }
    return true;
  }

  private static Exemption exempt(HttpMethod method, String pattern) {
    return new Exemption(method.name(), PARSER.parse(pattern));
  }

  private record Exemption(String method, PathPattern pattern) {

    boolean matches(String requestMethod, String path) {
      return method.equalsIgnoreCase(requestMethod)
          && pattern.matches(PathContainer.parsePath(path));
    }
  }
}
