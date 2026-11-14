package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.dto.request.EmailVerificationRequest;
import com.sairo.be.domain.auth.dto.request.EmailVerifyRequest;
import com.sairo.be.domain.auth.dto.request.LinkEmailRequest;
import com.sairo.be.domain.auth.dto.request.LinkKakaoRequest;
import com.sairo.be.domain.auth.dto.response.EmailVerifyResponse;
import com.sairo.be.domain.auth.dto.response.LoginMethodLinkResponse;
import com.sairo.be.domain.auth.dto.response.VerificationSendResponse;
import com.sairo.be.domain.auth.service.EmailVerificationService;
import com.sairo.be.domain.auth.service.KakaoAuthFlow;
import com.sairo.be.domain.auth.service.KakaoAuthorizationService;
import com.sairo.be.domain.auth.service.LoginMethodService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/me/login-methods")
@RequiresAccess(AccessPermission.AUTHENTICATED)
@Tag(name = "LOGIN_METHOD", description = "로그인수단 연결과 해제")
public class LoginMethodController {

  private final LoginMethodService loginMethodService;
  private final EmailVerificationService emailVerificationService;
  private final KakaoAuthorizationService authorizationService;

  public LoginMethodController(
      LoginMethodService loginMethodService,
      EmailVerificationService emailVerificationService,
      KakaoAuthorizationService authorizationService) {
    this.loginMethodService = loginMethodService;
    this.emailVerificationService = emailVerificationService;
    this.authorizationService = authorizationService;
  }

  @Operation(summary = "이메일 연결 인증 링크 발송", description = "기존 계정에 연결할 이메일의 인증 링크를 발송한다.")
  @PostMapping("/email/verification")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public VerificationSendResponse sendEmailVerification(
      @Valid @RequestBody EmailVerificationRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    loginMethodService.requireEmailLinkable(principal.userId());
    return emailVerificationService.sendLink(principal.userId(), request.email());
  }

  @Operation(summary = "이메일 연결 인증 토큰 확인", description = "링크 토큰을 소진하고 이메일 인증 티켓을 발급한다.")
  @PostMapping("/email/verify")
  public EmailVerifyResponse verifyEmail(
      @Valid @RequestBody EmailVerifyRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpServletRequest httpRequest) {
    return emailVerificationService.verifyLink(
        principal.userId(), request.token(), ClientRequestInfo.from(httpRequest).ipAddress());
  }

  @Operation(summary = "이메일 로그인수단 연결 완료", description = "재인증을 마친 상태에서 인증된 이메일과 비밀번호를 연결한다.")
  @PostMapping("/email")
  public LoginMethodLinkResponse linkEmail(
      @Valid @RequestBody LinkEmailRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    return loginMethodService.linkEmail(principal.userId(), request, session);
  }

  @Operation(summary = "카카오 연결 인가 시작", description = "재인증을 마친 상태에서 카카오 인가 페이지로 이동(302)한다.")
  @GetMapping("/kakao/start")
  public void startKakao(
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session,
      HttpServletResponse response)
      throws IOException {
    loginMethodService.requireKakaoLinkable(principal.userId(), session);
    response.sendRedirect(authorizationService.start(session, KakaoAuthFlow.LINK));
  }

  @Operation(summary = "카카오 로그인수단 연결", description = "카카오 인가 콜백의 code와 state를 받아 카카오를 연결한다.")
  @PostMapping("/kakao")
  public LoginMethodLinkResponse linkKakao(
      @Valid @RequestBody LinkKakaoRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    loginMethodService.requireKakaoLinkable(principal.userId(), session);
    String providerKey =
        authorizationService
            .resolveProviderKey(session, KakaoAuthFlow.LINK, request.code(), request.state(), null)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
    return loginMethodService.linkKakao(principal.userId(), providerKey, session);
  }

  @Operation(summary = "로그인수단 해제", description = "재인증을 마친 상태에서 로그인수단을 해제한다. 마지막 남은 수단은 409다.")
  @DeleteMapping("/{method}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unlink(
      @PathVariable String method,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    loginMethodService.unlink(principal.userId(), method, session);
  }
}
