package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsMfaVerifyRequest;
import com.sairo.be.domain.ops.dto.request.OpsStepUpRequest;
import com.sairo.be.domain.ops.dto.response.OpsMeResponse;
import com.sairo.be.domain.ops.dto.response.OpsMfaVerifyResponse;
import com.sairo.be.domain.ops.dto.response.OpsStepUpResponse;
import com.sairo.be.domain.ops.service.OperatorAuthService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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
@RequestMapping("/api/ops")
@Tag(name = "OPS", description = "운영자 로그인과 2차 인증")
public class OperatorAuthController {

  private final OperatorAuthService authService;

  public OperatorAuthController(OperatorAuthService authService) {
    this.authService = authService;
  }

  @Operation(summary = "운영자 카카오 인증 시작", description = "카카오 인가 페이지로 이동(302)한다.")
  @SecurityRequirements
  @GetMapping("/auth/kakao/start")
  @RequiresAccess(AccessPermission.PUBLIC)
  public void startKakao(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    authService.startLogin(request, response);
  }

  @Operation(
      summary = "운영자 카카오 인증 콜백",
      description = "화이트리스트와 활성 상태를 확인한 뒤 2차 인증 대기 쿠키를 발급하고 이동(302)한다.")
  @SecurityRequirements
  @GetMapping("/auth/kakao/callback")
  @RequiresAccess(AccessPermission.PUBLIC)
  public void kakaoCallback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    authService.completeKakaoLogin(request, response, code, state, error);
  }

  @Operation(summary = "2차 인증 코드 확인", description = "대기 쿠키를 운영자 세션으로 바꾼다. 5회 연속 실패하면 15분 잠근다.")
  @SecurityRequirements
  @PostMapping("/auth/mfa/verify")
  @RequiresAccess(AccessPermission.PUBLIC)
  public OpsMfaVerifyResponse verifyMfa(
      @Valid @RequestBody OpsMfaVerifyRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    return authService.verifyMfa(request, response, body.otpCode());
  }

  @Operation(summary = "고위험 작업용 5분 재인증")
  @PostMapping("/auth/step-up")
  @RequiresAccess(AccessPermission.OPERATOR)
  public OpsStepUpResponse stepUp(
      @Valid @RequestBody OpsStepUpRequest body,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return authService.stepUp(principal, session, body.otpCode());
  }

  @Operation(summary = "운영자 로그아웃")
  @PostMapping("/auth/logout")
  @RequiresAccess(AccessPermission.OPERATOR)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request) {
    authService.logout(request);
  }

  @Operation(summary = "로그인한 운영자 정보와 역할 조회")
  @GetMapping("/me")
  @RequiresAccess(AccessPermission.OPERATOR)
  public OpsMeResponse me(@AuthenticationPrincipal OperatorPrincipal principal) {
    return authService.me(principal);
  }
}
