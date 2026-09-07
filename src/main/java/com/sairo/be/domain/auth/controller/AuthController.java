package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.service.AuthService;
import com.sairo.be.global.security.AbsoluteSessionTimeoutFilter;
import com.sairo.be.global.security.StaffAuthentication;
import com.sairo.be.global.security.StaffPrincipal;
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
public class AuthController {

  private static final String STATE_SESSION_ATTRIBUTE = "KAKAO_OAUTH_STATE";
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final AuthService authService;
  private final String frontendBaseUrl;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  public AuthController(
      AuthService authService,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.authService = authService;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  @GetMapping("/start")
  public void start(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String state = generateState();
    request.getSession(true).setAttribute(STATE_SESSION_ATTRIBUTE, state);
    response.sendRedirect(authService.buildAuthorizeUrl(state));
  }

  @GetMapping("/callback")
  public void callback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    if (error != null || code == null || !isValidState(request, state)) {
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
    Object expected = session.getAttribute(STATE_SESSION_ATTRIBUTE);
    session.removeAttribute(STATE_SESSION_ATTRIBUTE);
    return expected != null && expected.equals(state);
  }

  private void establishSession(
      HttpServletRequest request, HttpServletResponse response, StaffPrincipal principal) {
    request.changeSessionId();

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
