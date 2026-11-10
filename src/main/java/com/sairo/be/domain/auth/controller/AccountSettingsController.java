package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.dto.request.ContactChangeRequest;
import com.sairo.be.domain.auth.dto.request.EmailVerificationRequest;
import com.sairo.be.domain.auth.dto.request.EmailVerifyRequest;
import com.sairo.be.domain.auth.dto.request.MeUpdateRequest;
import com.sairo.be.domain.auth.dto.request.NotificationPreferenceUpdateRequest;
import com.sairo.be.domain.auth.dto.response.ContactChangeResponse;
import com.sairo.be.domain.auth.dto.response.EmailVerifyResponse;
import com.sairo.be.domain.auth.dto.response.MeUpdateResponse;
import com.sairo.be.domain.auth.dto.response.NotificationPreferenceUpdateResponse;
import com.sairo.be.domain.auth.dto.response.TermsAgreementHistoryItemResponse;
import com.sairo.be.domain.auth.dto.response.VerificationSendResponse;
import com.sairo.be.domain.auth.dto.response.WithdrawalPreviewResponse;
import com.sairo.be.domain.auth.service.AccountSettingsService;
import com.sairo.be.domain.auth.service.ContactChangeService;
import com.sairo.be.domain.auth.service.TermsHistoryService;
import com.sairo.be.domain.auth.service.WithdrawalService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/me")
@RequiresAccess(AccessPermission.AUTHENTICATED)
@Tag(name = "SETTING", description = "계정 설정, 연락처 변경, 동의 내역, 탈퇴")
public class AccountSettingsController {

  private final AccountSettingsService accountSettingsService;
  private final ContactChangeService contactChangeService;
  private final TermsHistoryService termsHistoryService;
  private final WithdrawalService withdrawalService;

  public AccountSettingsController(
      AccountSettingsService accountSettingsService,
      ContactChangeService contactChangeService,
      TermsHistoryService termsHistoryService,
      WithdrawalService withdrawalService) {
    this.accountSettingsService = accountSettingsService;
    this.contactChangeService = contactChangeService;
    this.termsHistoryService = termsHistoryService;
    this.withdrawalService = withdrawalService;
  }

  @Operation(summary = "표시 이름 수정", description = "이메일·휴대폰·로그인수단은 이 요청으로 바꾸지 않는다.")
  @PatchMapping
  public MeUpdateResponse updateMe(
      @Valid @RequestBody MeUpdateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return accountSettingsService.updateDisplayName(principal.userId(), request.displayName());
  }

  @Operation(
      summary = "알림 수신 채널 설정",
      description = "SECURITY는 끌 수 없다. MARKETING을 켜는 요청은 400이고 끄면 철회 기록과 결과 통지를 만든다.")
  @PatchMapping("/notification-preferences")
  public NotificationPreferenceUpdateResponse updateNotificationPreferences(
      @Valid @RequestBody NotificationPreferenceUpdateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return accountSettingsService.updateNotificationPreferences(
        principal.userId(), request.channels());
  }

  @Operation(summary = "이메일 변경 인증 링크 발송", description = "재인증을 마친 이메일 로그인 사용자가 새 주소로 인증 링크를 받는다.")
  @PostMapping("/contact/email/verification")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public VerificationSendResponse sendContactEmailVerification(
      @Valid @RequestBody EmailVerificationRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    return contactChangeService.sendEmailVerification(principal.userId(), request.email(), session);
  }

  @Operation(summary = "이메일 변경 인증 토큰 확인", description = "링크 토큰을 소진하고 이메일 인증 티켓을 발급한다.")
  @PostMapping("/contact/email/verify")
  public EmailVerifyResponse verifyContactEmail(
      @Valid @RequestBody EmailVerifyRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpServletRequest httpRequest,
      HttpSession session) {
    return contactChangeService.verifyEmail(
        principal.userId(),
        request.token(),
        ClientRequestInfo.from(httpRequest).ipAddress(),
        session);
  }

  @Operation(summary = "이메일·휴대폰 변경", description = "인증 티켓으로 이메일 또는 휴대폰을 바꾼다. 이메일 변경은 재인증이 필요하다.")
  @PatchMapping("/contact")
  public ContactChangeResponse changeContact(
      @Valid @RequestBody ContactChangeRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    return contactChangeService.change(principal.userId(), request, session);
  }

  @Operation(summary = "약관 동의·철회 이력 조회", description = "본인의 동의·철회 전체 이력을 최신순으로 돌려준다.")
  @GetMapping("/terms-agreements")
  public PageResponse<TermsAgreementHistoryItemResponse> termsAgreements(
      @AuthenticationPrincipal StaffPrincipal principal,
      @RequestParam(required = false) String termsCode,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return termsHistoryService.list(principal.userId(), termsCode, PageQuery.of(page, size));
  }

  @Operation(summary = "탈퇴 안내 미리보기", description = "탈퇴 효과와 파기·보관 항목을 돌려준다. 상태를 바꾸지 않는다.")
  @GetMapping("/withdrawal-preview")
  public WithdrawalPreviewResponse withdrawalPreview(
      @AuthenticationPrincipal StaffPrincipal principal) {
    return withdrawalService.preview(principal.userId());
  }

  @Operation(summary = "회원 탈퇴", description = "재인증 뒤 탈퇴를 실행한다. 유일한 대표는 409다.")
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void withdraw(@AuthenticationPrincipal StaffPrincipal principal, HttpSession session) {
    withdrawalService.withdraw(principal.userId(), session);
  }
}
