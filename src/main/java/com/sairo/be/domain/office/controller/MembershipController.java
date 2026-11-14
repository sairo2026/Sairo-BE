package com.sairo.be.domain.office.controller;

import com.sairo.be.domain.office.dto.request.MembershipCreateRequest;
import com.sairo.be.domain.office.dto.request.MembershipInviteRequest;
import com.sairo.be.domain.office.dto.request.MembershipReasonRequest;
import com.sairo.be.domain.office.dto.request.MembershipRoleUpdateRequest;
import com.sairo.be.domain.office.dto.response.MembershipDetailResponse;
import com.sairo.be.domain.office.dto.response.MembershipInvitationAcceptResponse;
import com.sairo.be.domain.office.dto.response.MembershipInvitationResponse;
import com.sairo.be.domain.office.dto.response.MembershipResultResponse;
import com.sairo.be.domain.office.dto.response.MembershipRoleUpdateResponse;
import com.sairo.be.domain.office.dto.response.MembershipStatusResponse;
import com.sairo.be.domain.office.dto.response.OfficeSearchItemResponse;
import com.sairo.be.domain.office.service.MembershipAdminService;
import com.sairo.be.domain.office.service.MembershipApplicationService;
import com.sairo.be.domain.office.service.MembershipDecisionService;
import com.sairo.be.domain.office.service.OfficeSearchService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api")
@Tag(name = "MEMBER", description = "소속 참여, 초대, 승인, 역할과 접근 관리")
public class MembershipController {

  private final OfficeSearchService searchService;
  private final MembershipApplicationService applicationService;
  private final MembershipDecisionService decisionService;
  private final MembershipAdminService adminService;

  public MembershipController(
      OfficeSearchService searchService,
      MembershipApplicationService applicationService,
      MembershipDecisionService decisionService,
      MembershipAdminService adminService) {
    this.searchService = searchService;
    this.applicationService = applicationService;
    this.decisionService = decisionService;
    this.adminService = adminService;
  }

  @Operation(summary = "참여할 사무소 검색", description = "인증 완료된 사무소만 사업자등록번호 또는 사무소명과 지역으로 찾는다.")
  @GetMapping("/offices/search")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public PageResponse<OfficeSearchItemResponse> search(
      @RequestParam(required = false) String businessRegistrationNumber,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String region,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return searchService.search(businessRegistrationNumber, name, region, PageQuery.of(page, size));
  }

  @Operation(summary = "기존 사무소 참여 요청")
  @PostMapping("/offices/{officeId}/memberships")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  @ResponseStatus(HttpStatus.CREATED)
  public MembershipResultResponse create(
      @PathVariable Long officeId,
      @RequestBody MembershipCreateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return applicationService.create(principal.userId(), officeId, request);
  }

  @Operation(summary = "본인의 최신 참여 요청·초대 상태 조회")
  @GetMapping("/memberships/me")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public MembershipStatusResponse latest(@AuthenticationPrincipal StaffPrincipal principal) {
    return applicationService.latest(principal.userId());
  }

  @Operation(summary = "참여 요청 취소")
  @PostMapping("/memberships/{membershipId}/cancel")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public MembershipResultResponse cancel(
      @PathVariable Long membershipId, @AuthenticationPrincipal StaffPrincipal principal) {
    return applicationService.cancel(principal.userId(), membershipId);
  }

  @Operation(summary = "참여 거절 재신청 시작", description = "거절된 행을 해결됨으로 표시하고 새 참여 요청을 만든다.")
  @PostMapping("/memberships/{membershipId}/reapplication-start")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public MembershipResultResponse startReapplication(
      @PathVariable Long membershipId,
      @RequestBody MembershipCreateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return applicationService.startReapplication(principal.userId(), membershipId, request);
  }

  @Operation(summary = "초대 수락", description = "초대 대상 본인이 자격 확인 입력과 함께 수락한다. 승인은 대표가 이어서 한다.")
  @PostMapping("/memberships/{membershipId}/accept-invitation")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public MembershipInvitationAcceptResponse acceptInvitation(
      @PathVariable Long membershipId,
      @RequestBody MembershipCreateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return applicationService.acceptInvitation(principal.userId(), membershipId, request);
  }

  @Operation(summary = "참여 요청 승인", description = "중개보조원 5배 한도를 넘으면 409다.")
  @PostMapping("/memberships/{membershipId}/approve")
  @RequiresAccess(AccessPermission.OWNER)
  public MembershipResultResponse approve(
      @PathVariable Long membershipId, @AuthenticationPrincipal StaffPrincipal principal) {
    return decisionService.approve(principal, membershipId);
  }

  @Operation(summary = "참여 요청 거절")
  @PostMapping("/memberships/{membershipId}/reject")
  @RequiresAccess(AccessPermission.OWNER)
  public MembershipResultResponse reject(
      @PathVariable Long membershipId,
      @Valid @RequestBody MembershipReasonRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return decisionService.reject(principal, membershipId, request.reason());
  }

  @Operation(summary = "구성원 초대", description = "사이로에 가입된 계정의 이메일로 대기 소속을 만들고 안내 메일을 보낸다.")
  @PostMapping("/offices/current/memberships/invite")
  @RequiresAccess(AccessPermission.OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public MembershipInvitationResponse invite(
      @Valid @RequestBody MembershipInviteRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return decisionService.invite(principal, request);
  }

  @Operation(summary = "초대 취소")
  @PostMapping("/memberships/{membershipId}/invitation/cancel")
  @RequiresAccess(AccessPermission.OWNER)
  public MembershipResultResponse cancelInvitation(
      @PathVariable Long membershipId, @AuthenticationPrincipal StaffPrincipal principal) {
    return decisionService.cancelInvitation(principal, membershipId);
  }

  @Operation(summary = "초대 재발송", description = "만료 시각을 새로 7일로 갱신하고 안내 메일을 다시 보낸다.")
  @PostMapping("/memberships/{membershipId}/invitation/resend")
  @RequiresAccess(AccessPermission.OWNER)
  public MembershipInvitationResponse resendInvitation(
      @PathVariable Long membershipId, @AuthenticationPrincipal StaffPrincipal principal) {
    return decisionService.resendInvitation(principal, membershipId);
  }

  @Operation(summary = "구성원 상세와 인계될 업무 건수", description = "대표 전용이다.")
  @GetMapping("/memberships/{membershipId}")
  @RequiresAccess(AccessPermission.OWNER)
  public MembershipDetailResponse detail(
      @PathVariable Long membershipId, @AuthenticationPrincipal StaffPrincipal principal) {
    return decisionService.detail(principal, membershipId);
  }

  @Operation(summary = "역할 변경", description = "재인증이 필요하고 If-Match에 소속 version을 보낸다.")
  @PatchMapping("/memberships/{membershipId}/role")
  @RequiresAccess(AccessPermission.OWNER)
  public MembershipRoleUpdateResponse changeRole(
      @PathVariable Long membershipId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody MembershipRoleUpdateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    return adminService.changeRole(
        principal, session, membershipId, IfMatchVersion.parse(ifMatch), request.role());
  }

  @Operation(summary = "접근정지")
  @PostMapping("/memberships/{membershipId}/suspend")
  @RequiresAccess(AccessPermission.OWNER)
  public MembershipResultResponse suspend(
      @PathVariable Long membershipId,
      @Valid @RequestBody MembershipReasonRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return adminService.suspend(principal, membershipId, request.reason());
  }

  @Operation(summary = "접근정지 해제")
  @PostMapping("/memberships/{membershipId}/reinstate")
  @RequiresAccess(AccessPermission.OWNER)
  public MembershipResultResponse reinstate(
      @PathVariable Long membershipId, @AuthenticationPrincipal StaffPrincipal principal) {
    return adminService.reinstate(principal, membershipId);
  }

  @Operation(summary = "소속 회수", description = "재인증이 필요하고 본문에 사유를 보낸다.")
  @DeleteMapping("/memberships/{membershipId}")
  @RequiresAccess(AccessPermission.OWNER)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(
      @PathVariable Long membershipId,
      @Valid @RequestBody MembershipReasonRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    adminService.revoke(principal, session, membershipId, request.reason());
  }

  @Operation(summary = "본인 소속 사무소에서 나가기", description = "유일한 대표는 409다. 계정은 유지한다.")
  @PostMapping("/memberships/me/leave")
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void leave(@AuthenticationPrincipal StaffPrincipal principal) {
    adminService.leave(principal);
  }
}
