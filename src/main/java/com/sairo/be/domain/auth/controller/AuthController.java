package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.service.AuthService;
import com.sairo.be.domain.auth.service.OAuthStateService;
import com.sairo.be.global.security.SessionIssuer;
import com.sairo.be.global.security.SessionKind;
import com.sairo.be.global.security.StaffAuthentication;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Profile("!migrate")
@RestController
@RequiresAccess(AccessPermission.PUBLIC)
@RequestMapping("/api/auth/kakao")
@Tag(name = "AUTH", description = "카카오 로그인으로 사무소 직원 세션을 발급한다.")
public class AuthController {

  private static final int OAUTH_START_SESSION_SECONDS = 600;

  private final AuthService authService;
  private final OAuthStateService oauthStateService;
  private final SessionIssuer sessionIssuer;
  private final String frontendBaseUrl;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  public AuthController(
      AuthService authService,
      OAuthStateService oauthStateService,
      SessionIssuer sessionIssuer,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.authService = authService;
    this.oauthStateService = oauthStateService;
    this.sessionIssuer = sessionIssuer;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  @Operation(
      summary = "카카오 로그인 시작",
      description = "카카오 로그인 페이지로 리다이렉트하며 CSRF 방지를 위한 state 값을 만료 시간이 있는 저장소에 한 번만 쓸 수 있게 저장한다.")
  @SecurityRequirements
  @GetMapping("/start")
  public void start(HttpServletRequest request, HttpServletResponse response) throws IOException {
    HttpSession session = request.getSession(true);
    session.setMaxInactiveInterval(OAUTH_START_SESSION_SECONDS);
    String state = oauthStateService.issue(session.getId());
    response.sendRedirect(authService.buildAuthorizeUrl(state));
  }

  @Operation(summary = "카카오 로그인 콜백", description = "카카오 인증 코드를 사무소 직원 세션으로 교환하고 프론트엔드로 리다이렉트한다.")
  @SecurityRequirements
  @GetMapping("/callback")
  public void callback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    boolean validState = isValidState(request, state);
    if (error != null || code == null || code.isBlank() || !validState) {
      log.warn(
          "카카오 로그인 콜백이 인가 단계에서 실패했습니다. error={}, codePresent={}, validState={}",
          error,
          code != null && !code.isBlank(),
          validState);
      response.sendRedirect(frontendBaseUrl + "/login?error=auth_failed");
      return;
    }

    Optional<StaffPrincipal> principal = authService.authenticate(code);
    if (principal.isEmpty()) {
      log.warn("카카오 로그인 콜백이 인증 단계에서 실패했습니다.");
      response.sendRedirect(frontendBaseUrl + "/login?error=auth_failed");
      return;
    }

    establishSession(request, response, principal.get());
    response.sendRedirect(frontendBaseUrl + "/");
  }

  private boolean isValidState(HttpServletRequest request, String state) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return false;
    }
    return oauthStateService.consume(session.getId(), state);
  }

  private void establishSession(
      HttpServletRequest request, HttpServletResponse response, StaffPrincipal principal) {
    sessionIssuer.issue(request, SessionKind.MEMBER);

    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new StaffAuthentication(principal));
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);

    issueCsrfCookie(request);
  }

  private void issueCsrfCookie(HttpServletRequest request) {
    Object csrfToken = request.getAttribute(CsrfToken.class.getName());
    if (csrfToken instanceof CsrfToken token) {
      token.getToken();
    }
  }
}
