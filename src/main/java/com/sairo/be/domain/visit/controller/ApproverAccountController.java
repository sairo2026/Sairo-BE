package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverConditionBulkApplyResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverConditionBulkPreviewResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverLinkDecisionResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverOtpSendResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverPortalApprovalListResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverProfileCloseResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverProfileResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverSessionResponse;
import com.sairo.be.domain.visit.service.ApproverAccountService;
import com.sairo.be.domain.visit.service.ApproverAccountService.CloseResult;
import com.sairo.be.domain.visit.service.ApproverConditionBulkService;
import com.sairo.be.domain.visit.service.ApproverOtpService;
import com.sairo.be.domain.visit.service.ApproverOtpService.Purpose;
import com.sairo.be.domain.visit.service.ApproverOtpService.Verified;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.ApproverAuthentication;
import com.sairo.be.global.security.ApproverPrincipal;
import com.sairo.be.global.security.ApproverSessionManager;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 승인자 선택적 계정의 인증번호 발송·확인, 세션, 프로필, 연결 제안, 조건 일괄 적용이다(VISIT-24, RESPOND-09·10). */
@Profile("!migrate")
@RestController
@Tag(name = "RESPOND", description = "승인자 계정")
public class ApproverAccountController {

  private final ApproverOtpService otpService;
  private final ApproverAccountService accountService;
  private final ApproverConditionBulkService bulkService;
  private final ApproverSessionManager sessionManager;

  public ApproverAccountController(
      ApproverOtpService otpService,
      ApproverAccountService accountService,
      ApproverConditionBulkService bulkService,
      ApproverSessionManager sessionManager) {
    this.otpService = otpService;
    this.accountService = accountService;
    this.bulkService = bulkService;
    this.sessionManager = sessionManager;
  }

  @Operation(
      summary = "승인자 계정 인증번호 발송",
      description = "계정 만들기는 링크 출입승인의 승인자 연락처와 번호가 같을 때만 보낸다. 로그인은 계정이 없어도 같은 응답이다.")
  @PostMapping("/api/public/approver-auth/otp")
  @RequiresAccess(AccessPermission.PUBLIC)
  public ResponseEntity<ApproverOtpSendResponse> otp(
      @RequestBody JsonNode body, HttpServletRequest request) {
    return noStore(HttpStatus.OK, otpService.send(body, sessionProfileId(), ip(request)));
  }

  @Operation(
      summary = "승인자 계정 인증번호 확인과 세션 발급",
      description = "계정 만들기는 프로필 생성과 링크 출입승인 연결을 함께 한다. 재인증은 현재 세션의 재인증 유효 시각을 10분 뒤로 정한다.")
  @PostMapping("/api/public/approver-auth/verify")
  @RequiresAccess(AccessPermission.PUBLIC)
  public ResponseEntity<ApproverSessionResponse> verify(
      @RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response) {
    Verified verified = otpService.verify(body, sessionProfileId(), ip(request));
    Instant issuedAt;
    Instant reauthUntil = null;
    if (verified.purpose() == Purpose.REAUTH) {
      HttpSession session = request.getSession(false);
      if (session == null) {
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
      }
      reauthUntil = sessionManager.grantReauth(session);
      issuedAt = sessionManager.issuedAt(session);
    } else {
      HttpSession session =
          sessionManager.begin(request, response, new ApproverPrincipal(verified.profileId()));
      issuedAt = sessionManager.issuedAt(session);
    }
    return noStore(
        HttpStatus.OK,
        accountService.sessionResponse(
            verified.profileId(),
            issuedAt,
            reauthUntil,
            verified.claimedApprovalId(),
            verified.profileCreated()));
  }

  @Operation(summary = "승인자 세션 로그아웃", description = "세션 쿠키를 만료시킨다.")
  @DeleteMapping("/api/approver-portal/session")
  @RequiresAccess(AccessPermission.APPROVER_SESSION)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request) {
    sessionManager.end(request);
  }

  @Operation(summary = "승인자 프로필 요약", description = "이름, 이메일, 번호 원문은 없다.")
  @GetMapping("/api/approver-portal/profile")
  @RequiresAccess(AccessPermission.APPROVER_SESSION)
  public ResponseEntity<ApproverProfileResponse> profile(
      @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(HttpStatus.OK, accountService.profile(principal.profileId()));
  }

  @Operation(
      summary = "승인자 계정 해지",
      description = "10분 이내 재인증이 필요하다. 번호 정보와 연결을 지우고 모든 세션을 끝낸다. 유효한 링크 방식 접근은 계속된다.")
  @PostMapping("/api/approver-portal/profile/close")
  @RequiresAccess(AccessPermission.APPROVER_SESSION)
  public ResponseEntity<ApproverProfileCloseResponse> close(
      @RequestBody JsonNode body,
      @AuthenticationPrincipal ApproverPrincipal principal,
      HttpServletRequest request) {
    if (sessionManager.reauthUntil(request.getSession(false)).isEmpty()) {
      throw new BusinessException(ErrorCode.STEP_UP_REQUIRED);
    }
    CustomerInput.requireObject(body, java.util.Set.of("confirm"));
    Boolean confirm = CustomerInput.requiredBoolean(body, "confirm");
    if (!Boolean.TRUE.equals(confirm)) {
      throw CustomerInput.invalid("confirm", FieldErrorCode.INVALID_VALUE, "true여야 합니다.");
    }
    CloseResult closed = accountService.close(principal.profileId(), ip(request));
    int ended = sessionManager.terminateAll(principal.profileId());
    sessionManager.end(request);
    return noStore(
        HttpStatus.OK,
        new ApproverProfileCloseResponse(
            TimeRules.toDisplayOffset(closed.closedAt()), closed.unlinkedApprovalCount(), ended));
  }

  @Operation(
      summary = "연결된 출입승인과 연결 제안 목록",
      description = "연결 제안은 수락 전까지 매물 표시명, 주소, 문의 전화, 신청과 확정 방문을 보여주지 않는다.")
  @GetMapping("/api/approver-portal/approvals")
  @RequiresAccess(AccessPermission.APPROVER_SESSION)
  public ResponseEntity<ApproverPortalApprovalListResponse> approvals(
      @RequestParam(required = false) String linkState,
      @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(HttpStatus.OK, accountService.approvals(principal.profileId(), linkState));
  }

  @Operation(summary = "연결 제안 수락·거절", description = "거절한 출입승인은 다시 제안하지 않는다.")
  @PostMapping("/api/approver-portal/approvals/{approvalId}/link-decision")
  @RequiresAccess(AccessPermission.APPROVER_SESSION)
  public ResponseEntity<ApproverLinkDecisionResponse> decide(
      @PathVariable Long approvalId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal ApproverPrincipal principal,
      HttpServletRequest request) {
    return noStore(
        HttpStatus.OK, accountService.decide(principal.profileId(), approvalId, body, ip(request)));
  }

  @Operation(summary = "출입승인 연결 해제", description = "해제한 출입승인은 다시 제안하지 않으며 입력한 조건과 선택 기록은 남는다.")
  @DeleteMapping("/api/approver-portal/approvals/{approvalId}/link")
  @RequiresAccess(AccessPermission.APPROVER_SESSION)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unlink(
      @PathVariable Long approvalId,
      @AuthenticationPrincipal ApproverPrincipal principal,
      HttpServletRequest request) {
    accountService.unlink(principal.profileId(), approvalId, ip(request));
  }

  @Operation(
      summary = "조건 일괄 적용 미리보기",
      description = "출입승인별 영향 방문을 반영 전에 안내한다. 연결되지 않았거나 대표 승인자가 아닌 출입승인은 대상 아님으로 알린다.")
  @PostMapping("/api/approver-portal/condition-bulk/preview")
  @RequiresAccess(AccessPermission.APPROVER_SESSION)
  public ResponseEntity<ApproverConditionBulkPreviewResponse> bulkPreview(
      @RequestBody JsonNode body, @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(HttpStatus.OK, bulkService.preview(principal.profileId(), body));
  }

  @Operation(
      summary = "조건 일괄 적용",
      description = "출입승인마다 독립 트랜잭션으로 새 조건 버전을 만든다. 일부가 적용되지 않아도 200이다.")
  @PostMapping("/api/approver-portal/condition-bulk")
  @RequiresAccess(AccessPermission.APPROVER_SESSION)
  public ResponseEntity<ApproverConditionBulkApplyResponse> bulkApply(
      @RequestBody JsonNode body,
      @AuthenticationPrincipal ApproverPrincipal principal,
      HttpServletRequest request) {
    return noStore(HttpStatus.OK, bulkService.apply(principal.profileId(), body, ip(request)));
  }

  private static Long sessionProfileId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof ApproverAuthentication approver) {
      return approver.getPrincipal().profileId();
    }
    return null;
  }

  private static String ip(HttpServletRequest request) {
    return ClientRequestInfo.from(request).ipAddress();
  }

  private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
  }
}
