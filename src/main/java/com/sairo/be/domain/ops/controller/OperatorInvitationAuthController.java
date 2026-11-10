package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OperatorInvitationAcceptRequest;
import com.sairo.be.domain.ops.dto.request.OperatorInvitationKakaoStartRequest;
import com.sairo.be.domain.ops.dto.request.OperatorInvitationPreviewRequest;
import com.sairo.be.domain.ops.dto.response.OperatorInvitationAcceptResponse;
import com.sairo.be.domain.ops.dto.response.OperatorInvitationKakaoStartResponse;
import com.sairo.be.domain.ops.dto.response.OperatorInvitationPreviewResponse;
import com.sairo.be.domain.ops.dto.response.OperatorMfaEnrollmentResponse;
import com.sairo.be.domain.ops.service.OperatorInvitationAuthService;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops/auth/invitations")
@RequiresAccess(AccessPermission.PUBLIC)
@SecurityRequirements
@Tag(name = "OPS", description = "운영자 초대 수락")
public class OperatorInvitationAuthController {

  private final OperatorInvitationAuthService invitationService;

  public OperatorInvitationAuthController(OperatorInvitationAuthService invitationService) {
    this.invitationService = invitationService;
  }

  @Operation(summary = "초대 확인", description = "토큰으로 초대 대상 이름, 부여 역할, 만료 시각을 돌려준다.")
  @PostMapping("/preview")
  public OperatorInvitationPreviewResponse preview(
      @Valid @RequestBody OperatorInvitationPreviewRequest body, HttpServletRequest request) {
    return invitationService.preview(body.token(), ClientRequestInfo.from(request).ipAddress());
  }

  @Operation(summary = "초대 수락자의 카카오 인증 시작")
  @PostMapping("/kakao-start")
  public OperatorInvitationKakaoStartResponse kakaoStart(
      @Valid @RequestBody OperatorInvitationKakaoStartRequest body, HttpServletRequest request) {
    return invitationService.startKakao(
        body.token(), ClientRequestInfo.from(request).ipAddress(), request);
  }

  @Operation(summary = "초대 수락자의 카카오 인증 콜백", description = "등록 쿠키를 발급하고 2차 인증 등록 화면으로 이동(302)한다.")
  @GetMapping("/kakao/callback")
  public void kakaoCallback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    invitationService.completeKakao(
        request, response, ClientRequestInfo.from(request).ipAddress(), code, state, error);
  }

  @Operation(summary = "2차 인증 비밀값 발급", description = "호출할 때마다 새 비밀값으로 이전 값을 대체한다.")
  @PostMapping("/mfa-enrollment")
  public OperatorMfaEnrollmentResponse enrollMfa(HttpServletRequest request) {
    return invitationService.enrollMfa(
        request.getSession(false), ClientRequestInfo.from(request).ipAddress());
  }

  @Operation(summary = "초대 수락", description = "확인 코드가 맞으면 운영자 계정과 역할을 만들고 세션을 발급한다.")
  @PostMapping("/accept")
  @ResponseStatus(HttpStatus.CREATED)
  public OperatorInvitationAcceptResponse accept(
      @Valid @RequestBody OperatorInvitationAcceptRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    return invitationService.accept(request, response, body.otpCode());
  }
}
