package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsConsoleRequests.MemberEmergencySuspendRequest;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.MemberEmergencySuspendResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorMemberDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorMemberItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorOfficeDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorOfficeItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OpsHomeResponse;
import com.sairo.be.domain.ops.service.OpsHomeService;
import com.sairo.be.domain.ops.service.OpsMemberService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops")
@RequiresAccess(AccessPermission.OPERATOR)
@Tag(name = "OPS", description = "운영 홈·고객사·회원 조회")
public class OpsConsoleController {

  private final OpsHomeService homeService;
  private final OpsMemberService memberService;

  public OpsConsoleController(OpsHomeService homeService, OpsMemberService memberService) {
    this.homeService = homeService;
    this.memberService = memberService;
  }

  @Operation(
      summary = "운영 홈 큐 요약",
      description = "심사 대기·기한 초과, 미배정 문의, 결제 확인 중 등 큐 건수다. 경영지표는 EXEC_VIEW 역할에만 담는다.")
  @GetMapping("/home")
  public OpsHomeResponse home(@AuthenticationPrincipal OperatorPrincipal principal) {
    return homeService.home(principal);
  }

  @Operation(summary = "고객사 목록", description = "keyword는 사무소명·사업자등록번호·개설등록번호 부분 일치다.")
  @GetMapping("/offices")
  public PageResponse<OperatorOfficeItem> offices(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String registrationStatus,
      @RequestParam(required = false) String subscriptionStatus,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return homeService.offices(
        keyword, registrationStatus, subscriptionStatus, PageQuery.of(page, size));
  }

  @Operation(summary = "고객사 상세", description = "기본정보·인증이력·구성원·고용명부 요약·구독·문의·반환·보관 탭 정보다.")
  @GetMapping("/offices/{officeId}")
  @RequiresOperatorRole({
    OperatorRole.SECURITY_ADMIN,
    OperatorRole.REVIEW,
    OperatorRole.SUPPORT,
    OperatorRole.BILLING,
    OperatorRole.MIGRATION,
    OperatorRole.PRIVACY,
    OperatorRole.OPS
  })
  public OperatorOfficeDetailResponse office(@PathVariable Long officeId) {
    return homeService.office(officeId);
  }

  @Operation(
      summary = "전체 회원 조회",
      description = "keyword는 회원 이름·소속 사무소명·사업자등록번호·개설등록번호 부분 일치다. 이메일·전화번호는 담지 않는다.")
  @GetMapping("/members")
  @RequiresOperatorRole({
    OperatorRole.SECURITY_ADMIN,
    OperatorRole.REVIEW,
    OperatorRole.SUPPORT,
    OperatorRole.BILLING,
    OperatorRole.MIGRATION,
    OperatorRole.PRIVACY,
    OperatorRole.OPS
  })
  public PageResponse<OperatorMemberItem> members(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String accountStatus,
      @RequestParam(required = false) String registrationStatus,
      @RequestParam(required = false) String subscriptionStatus,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return memberService.list(
        keyword, accountStatus, registrationStatus, subscriptionStatus, PageQuery.of(page, size));
  }

  @Operation(summary = "회원 상세", description = "상태·소속·로그인 방식·활성 세션·보안 사건이다. 이메일과 카카오 식별값은 담지 않는다.")
  @GetMapping("/members/{userId}")
  @RequiresOperatorRole({
    OperatorRole.SECURITY_ADMIN,
    OperatorRole.REVIEW,
    OperatorRole.SUPPORT,
    OperatorRole.BILLING,
    OperatorRole.MIGRATION,
    OperatorRole.PRIVACY,
    OperatorRole.OPS
  })
  public OperatorMemberDetailResponse member(@PathVariable Long userId) {
    return memberService.detail(userId);
  }

  @Operation(
      summary = "회원 긴급 접근 중단",
      description = "대상 세션을 즉시 끝내고 소속 사무소 대표에게 알린다. 회원 계정 상태는 바꾸지 않는다.")
  @PostMapping("/members/{userId}/emergency-suspend")
  @RequiresOperatorRole(OperatorRole.SECURITY_ADMIN)
  public MemberEmergencySuspendResponse emergencySuspend(
      @PathVariable Long userId,
      @Valid @RequestBody MemberEmergencySuspendRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return memberService.emergencySuspend(principal.operatorId(), userId, request);
  }
}
