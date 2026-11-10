package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.dto.request.EmailVerificationRequest;
import com.sairo.be.domain.auth.dto.request.EmailVerifyRequest;
import com.sairo.be.domain.auth.dto.request.LoginRequest;
import com.sairo.be.domain.auth.dto.request.PhoneVerificationSendRequest;
import com.sairo.be.domain.auth.dto.request.PhoneVerifyRequest;
import com.sairo.be.domain.auth.dto.request.SignupEmailRequest;
import com.sairo.be.domain.auth.dto.response.AccessConditionResponse;
import com.sairo.be.domain.auth.dto.response.AuthContextResponse;
import com.sairo.be.domain.auth.dto.response.EmailVerifyResponse;
import com.sairo.be.domain.auth.dto.response.PhoneVerifyResponse;
import com.sairo.be.domain.auth.dto.response.RecoveryOptionsResponse;
import com.sairo.be.domain.auth.dto.response.VerificationSendResponse;
import com.sairo.be.domain.auth.mapper.AuthMapper;
import com.sairo.be.domain.auth.service.AccessContextService;
import com.sairo.be.domain.auth.service.EmailLoginService;
import com.sairo.be.domain.auth.service.EmailSignupService;
import com.sairo.be.domain.auth.service.EmailVerificationService;
import com.sairo.be.domain.auth.service.PhoneVerificationService;
import com.sairo.be.domain.auth.service.ReconsentService;
import com.sairo.be.domain.auth.service.RecoveryOptionsService;
import com.sairo.be.global.security.MemberSessionManager;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/auth")
@Tag(name = "AUTH", description = "이메일 가입·로그인, 본인확인, 접근 상태 판정")
public class AuthSessionController {

  private final EmailLoginService emailLoginService;
  private final EmailSignupService emailSignupService;
  private final EmailVerificationService emailVerificationService;
  private final PhoneVerificationService phoneVerificationService;
  private final RecoveryOptionsService recoveryOptionsService;
  private final AccessContextService accessContextService;
  private final ReconsentService reconsentService;
  private final MemberSessionManager sessionManager;

  public AuthSessionController(
      EmailLoginService emailLoginService,
      EmailSignupService emailSignupService,
      EmailVerificationService emailVerificationService,
      PhoneVerificationService phoneVerificationService,
      RecoveryOptionsService recoveryOptionsService,
      AccessContextService accessContextService,
      ReconsentService reconsentService,
      MemberSessionManager sessionManager) {
    this.emailLoginService = emailLoginService;
    this.emailSignupService = emailSignupService;
    this.emailVerificationService = emailVerificationService;
    this.phoneVerificationService = phoneVerificationService;
    this.recoveryOptionsService = recoveryOptionsService;
    this.accessContextService = accessContextService;
    this.reconsentService = reconsentService;
    this.sessionManager = sessionManager;
  }

  @Operation(summary = "접근 상태 판정 조회", description = "접근 상태 8조건 판정 결과와 약관 재동의 필요 여부를 돌려준다.")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  @GetMapping("/context")
  public AuthContextResponse context(@AuthenticationPrincipal StaffPrincipal principal) {
    return AuthMapper.toAuthContext(
        accessContextService.judge(principal.userId()),
        reconsentService.isReconsentRequired(principal.userId()));
  }

  @Operation(summary = "이메일 로그인", description = "이메일과 비밀번호로 로그인하고 세션을 발급한다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @PostMapping("/login")
  public AccessConditionResponse login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    StaffPrincipal principal =
        emailLoginService.login(
            request.email(), request.password(), ClientRequestInfo.from(httpRequest));
    sessionManager.begin(httpRequest, httpResponse, principal);
    return AuthMapper.toAccessCondition(accessContextService.judge(principal.userId()));
  }

  @Operation(summary = "로그아웃", description = "현재 세션만 종료한다.")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request) {
    sessionManager.end(request);
  }

  @Operation(summary = "이메일 인증 링크 발송", description = "회원가입용 이메일 인증 링크를 발송한다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @PostMapping("/signup/email/verification")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public VerificationSendResponse sendSignupEmailVerification(
      @Valid @RequestBody EmailVerificationRequest request) {
    return emailVerificationService.sendSignup(request.email());
  }

  @Operation(summary = "이메일 인증 완료", description = "인증 토큰을 소진하고 서명된 이메일 인증 티켓을 발급한다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @PostMapping("/signup/email/verify")
  public EmailVerifyResponse verifySignupEmail(
      @Valid @RequestBody EmailVerifyRequest request, HttpServletRequest httpRequest) {
    return emailVerificationService.verifySignup(
        request.token(), ClientRequestInfo.from(httpRequest).ipAddress());
  }

  @Operation(summary = "휴대폰 인증번호 발송", description = "가입과 휴대폰 변경 공용 인증번호 문자를 발송한다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @PostMapping("/phone-verification")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public VerificationSendResponse sendPhoneVerification(
      @Valid @RequestBody PhoneVerificationSendRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpServletRequest httpRequest) {
    return phoneVerificationService.send(
        principal == null ? null : principal.userId(),
        request.phone(),
        request.purpose(),
        ClientRequestInfo.from(httpRequest).ipAddress());
  }

  @Operation(summary = "휴대폰 인증번호 확인", description = "인증번호를 확인하고 서명된 휴대폰 본인확인 티켓을 발급한다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @PostMapping("/phone-verification/verify")
  public PhoneVerifyResponse verifyPhone(
      @Valid @RequestBody PhoneVerifyRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpServletRequest httpRequest) {
    return phoneVerificationService.verify(
        principal == null ? null : principal.userId(),
        request.phone(),
        request.purpose(),
        request.code(),
        ClientRequestInfo.from(httpRequest).ipAddress());
  }

  @Operation(summary = "이메일 회원가입 완료", description = "두 인증 티켓과 약관 동의를 확인하고 계정과 세션을 만든다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @PostMapping("/signup/email")
  @ResponseStatus(HttpStatus.CREATED)
  public AccessConditionResponse signupEmail(
      @Valid @RequestBody SignupEmailRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    StaffPrincipal principal =
        emailSignupService.signup(request, ClientRequestInfo.from(httpRequest));
    sessionManager.begin(httpRequest, httpResponse, principal);
    return AuthMapper.toAccessCondition(accessContextService.judge(principal.userId()));
  }

  @Operation(summary = "계정 복구 안내", description = "소유가 확인된 이메일 또는 휴대폰 티켓으로 기존 계정의 로그인·복구 경로를 안내한다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @GetMapping("/recovery-options")
  public RecoveryOptionsResponse recoveryOptions(
      @RequestParam(required = false) String verifiedEmailTicket,
      @RequestParam(required = false) String verifiedPhoneTicket,
      HttpServletRequest httpRequest) {
    return recoveryOptionsService.find(
        verifiedEmailTicket, verifiedPhoneTicket, ClientRequestInfo.from(httpRequest).ipAddress());
  }
}
