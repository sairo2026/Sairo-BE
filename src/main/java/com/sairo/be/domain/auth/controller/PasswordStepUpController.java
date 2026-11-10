package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.dto.request.PasswordResetLinkRequest;
import com.sairo.be.domain.auth.dto.request.PasswordResetRequest;
import com.sairo.be.domain.auth.dto.request.StepUpPasswordRequest;
import com.sairo.be.domain.auth.dto.response.PasswordResetLinkResponse;
import com.sairo.be.domain.auth.dto.response.StepUpResponse;
import com.sairo.be.domain.auth.service.KakaoAuthFlow;
import com.sairo.be.domain.auth.service.KakaoAuthorizationService;
import com.sairo.be.domain.auth.service.PasswordResetService;
import com.sairo.be.domain.auth.service.StepUpService;
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
@Tag(name = "AUTH", description = "비밀번호 재설정과 재인증")
public class PasswordStepUpController {

  private final PasswordResetService passwordResetService;
  private final StepUpService stepUpService;
  private final KakaoAuthorizationService authorizationService;

  public PasswordStepUpController(
      PasswordResetService passwordResetService,
      StepUpService stepUpService,
      KakaoAuthorizationService authorizationService) {
    this.passwordResetService = passwordResetService;
    this.stepUpService = stepUpService;
    this.authorizationService = authorizationService;
  }

  @Operation(summary = "비밀번호 재설정 링크 발송", description = "가입 여부와 관계없이 같은 응답을 돌려준다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @PostMapping("/password/reset-request")
  public PasswordResetLinkResponse requestReset(
      @Valid @RequestBody PasswordResetLinkRequest request, HttpServletRequest httpRequest) {
    return passwordResetService.requestReset(
        request.email(), ClientRequestInfo.from(httpRequest).ipAddress());
  }

  @Operation(summary = "비밀번호 재설정 완료", description = "토큰을 소진하고 비밀번호를 바꾼 뒤 기존 세션을 모두 종료한다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @PostMapping("/password/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(
      @Valid @RequestBody PasswordResetRequest request, HttpServletRequest httpRequest) {
    passwordResetService.reset(
        request.token(), request.newPassword(), ClientRequestInfo.from(httpRequest).ipAddress());
  }

  @Operation(summary = "비밀번호 재인증", description = "이메일 계정의 비밀번호를 다시 받아 10분 재인증 상태를 기록한다.")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  @PostMapping("/step-up/password")
  public StepUpResponse stepUpWithPassword(
      @Valid @RequestBody StepUpPasswordRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    return stepUpService.withPassword(principal.userId(), request.password(), session);
  }

  @Operation(summary = "카카오 재인증 시작", description = "카카오 인가 페이지로 이동(302)한다.")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  @GetMapping("/step-up/kakao/start")
  public void startKakaoStepUp(
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session,
      HttpServletResponse response)
      throws IOException {
    stepUpService.requireKakaoStepUpAllowed(principal.userId());
    response.sendRedirect(authorizationService.start(session, KakaoAuthFlow.STEP_UP));
  }

  @Operation(summary = "카카오 재인증 콜백", description = "현재 회원과 같은 카카오 계정이면 10분 재인증 상태를 기록한다.")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  @GetMapping("/step-up/kakao/callback")
  public StepUpResponse kakaoStepUpCallback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    String providerKey =
        authorizationService
            .resolveProviderKey(session, KakaoAuthFlow.STEP_UP, code, state, error)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
    return stepUpService.withKakao(principal.userId(), providerKey, session);
  }
}
