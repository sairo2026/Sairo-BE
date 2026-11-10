package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.dto.response.CurrentSessionResponse;
import com.sairo.be.domain.auth.dto.response.LoginHistoryItemResponse;
import com.sairo.be.domain.auth.dto.response.MeResponse;
import com.sairo.be.domain.auth.mapper.AuthMapper;
import com.sairo.be.domain.auth.service.LoginHistoryService;
import com.sairo.be.domain.auth.service.MeQueryService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/me")
@RequiresAccess(AccessPermission.AUTHENTICATED)
@Tag(name = "ME", description = "내 계정, 세션, 로그인 이력")
public class MeController {

  private final MeQueryService meQueryService;
  private final LoginHistoryService loginHistoryService;
  private final StepUpState stepUpState;

  public MeController(
      MeQueryService meQueryService,
      LoginHistoryService loginHistoryService,
      StepUpState stepUpState) {
    this.meQueryService = meQueryService;
    this.loginHistoryService = loginHistoryService;
    this.stepUpState = stepUpState;
  }

  @Operation(summary = "내 계정 조회", description = "기본정보, 연결 로그인수단, 알림 수신 설정, 약관 동의 현황을 돌려준다.")
  @GetMapping
  public MeResponse me(@AuthenticationPrincipal StaffPrincipal principal) {
    return meQueryService.getMe(principal.userId());
  }

  @Operation(summary = "현재 세션 확인", description = "idle·absolute 만료 시각과 재인증 유효 여부를 돌려준다.")
  @GetMapping("/session")
  public CurrentSessionResponse session(HttpSession session) {
    return AuthMapper.toCurrentSession(session, stepUpState.validUntil(session));
  }

  @Operation(summary = "최근 로그인 조회", description = "로그인 기록을 최신순으로 돌려준다.")
  @GetMapping("/login-history")
  public PageResponse<LoginHistoryItemResponse> loginHistory(
      @AuthenticationPrincipal StaffPrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return loginHistoryService.list(principal.userId(), PageQuery.of(page, size));
  }
}
