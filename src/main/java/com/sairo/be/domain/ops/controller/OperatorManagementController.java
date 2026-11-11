package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsConsoleRequests.OperatorDisableRequest;
import com.sairo.be.domain.ops.dto.request.OpsConsoleRequests.OperatorInvitationCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsConsoleRequests.OperatorRoleAssignRequest;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorDisableResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorEnableResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorInvitationCancelResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorInvitationCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorInvitationItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OpsAuditLogItem;
import com.sairo.be.domain.ops.service.OperatorManagementService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/ops")
@RequiresAccess(AccessPermission.OPERATOR)
@RequiresOperatorRole(OperatorRole.SECURITY_ADMIN)
@Tag(name = "OPS", description = "운영자 계정·역할·초대와 감사 이력")
public class OperatorManagementController {

  private final OperatorManagementService service;

  public OperatorManagementController(OperatorManagementService service) {
    this.service = service;
  }

  @Operation(summary = "운영자 계정·역할 목록")
  @GetMapping("/operators")
  public PageResponse<OperatorItem> operators(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    return service.operators(PageQuery.of(page, size));
  }

  @Operation(
      summary = "운영자 역할 배정·회수",
      description = "보낸 roles가 배정 뒤의 전체 역할이다. 5분 재인증이 필요하고 마지막 활성 보안관리자의 역할은 회수할 수 없다.")
  @PostMapping("/operators/{operatorId}/roles")
  public OperatorItem assignRoles(
      @PathVariable Long operatorId,
      @Valid @RequestBody OperatorRoleAssignRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.assignRoles(principal.operatorId(), operatorId, request, session);
  }

  @Operation(
      summary = "운영자 계정 비활성화",
      description = "5분 재인증이 필요하다. 세션을 즉시 끝내고 그 운영자가 걸린 미실행 원문 접근 승인을 무효로 만든다.")
  @PostMapping("/operators/{operatorId}/disable")
  public OperatorDisableResponse disable(
      @PathVariable Long operatorId,
      @Valid @RequestBody OperatorDisableRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.disable(principal.operatorId(), operatorId, request, session);
  }

  @Operation(summary = "운영자 계정 재활성화", description = "5분 재인증이 필요하다. 역할 배정은 그대로다.")
  @PostMapping("/operators/{operatorId}/enable")
  public OperatorEnableResponse enable(
      @PathVariable Long operatorId,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.enable(principal.operatorId(), operatorId, session);
  }

  @Operation(
      summary = "운영자 초대 생성",
      description = "5분 재인증이 필요하다. 1회용 토큰의 해시만 저장하고 원문은 초대 메일 링크로만 보낸다. 유효기간은 72시간이다.")
  @PostMapping("/operator-invitations")
  @ResponseStatus(HttpStatus.CREATED)
  public OperatorInvitationCreateResponse createInvitation(
      @Valid @RequestBody OperatorInvitationCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.createInvitation(principal.operatorId(), request, session);
  }

  @Operation(summary = "운영자 초대 목록", description = "만료 시각이 지난 대기 초대는 EXPIRED로 돌려준다.")
  @GetMapping("/operator-invitations")
  public PageResponse<OperatorInvitationItem> invitations(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.invitations(status, PageQuery.of(page, size));
  }

  @Operation(summary = "대기 중 운영자 초대 취소", description = "5분 재인증과 If-Match(현재 version)가 필요하다.")
  @PostMapping("/operator-invitations/{invitationId}/cancel")
  public OperatorInvitationCancelResponse cancelInvitation(
      @PathVariable Long invitationId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.cancelInvitation(principal.operatorId(), invitationId, ifMatch, session);
  }

  @Operation(
      summary = "감사 이력 조회",
      description = "actionType·operatorId·targetType·targetId·from(포함)·to(제외)로 거른다. 최근 기록 순이다.")
  @GetMapping("/audit-log")
  public PageResponse<OpsAuditLogItem> auditLog(
      @RequestParam(required = false) String actionType,
      @RequestParam(required = false) Long operatorId,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) Long targetId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.auditLog(
        actionType, operatorId, targetType, targetId, from, to, PageQuery.of(page, size));
  }
}
