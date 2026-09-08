package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.service.AuthService;
import com.sairo.be.domain.auth.service.OAuthStateService;
import com.sairo.be.global.security.AbsoluteSessionTimeoutFilter;
import com.sairo.be.global.security.StaffAuthentication;
import com.sairo.be.global.security.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/auth/kakao")
@Tag(name = "AUTH", description = "카카오 로그인으로 사무소 직원 세션을 발급한다.")
public class AuthController {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final AuthService authService;
  private final OAuthStateService oauthStateService;
  private final String frontendBaseUrl;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  public AuthController(
      AuthService authService,
      OAuthStateService oauthStateService,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.authService = authService;
    this.oauthStateService = oauthStateService;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  @Operation(
      summary = "카카오 로그인 시작",
      description = "카카오 로그인 페이지로 리다이렉트하며 CSRF 방지를 위한 state 값을 세션에 저장한다.")
  @SecurityRequirements
  @GetMapping("/start")
  public void start(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String state = generateState();
    request.getSession(true).setAttribute(OAuthStateService.STATE_SESSION_ATTRIBUTE, state);
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
      response.sendRedirect(frontendBaseUrl + "/login?error=auth_failed");
      return;
    }

    Optional<StaffPrincipal> principal = authService.authenticate(code);
    if (principal.isEmpty()) {
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
    Object expected = session.getAttribute(OAuthStateService.STATE_SESSION_ATTRIBUTE);
    return expected instanceof String expectedState
        && oauthStateService.consume(session.getId(), expectedState, state);
  }

  private void establishSession(
      HttpServletRequest request, HttpServletResponse response, StaffPrincipal principal) {
    HttpSession previousSession = request.getSession(false);
    if (previousSession != null) {
      previousSession.invalidate();
    }
    request.getSession(true);

    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new StaffAuthentication(principal));
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);

    request
        .getSession(true)
        .setAttribute(AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE, Instant.now());
  }

  private String generateState() {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
