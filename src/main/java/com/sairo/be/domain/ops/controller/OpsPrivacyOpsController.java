package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.ArchiveGrantTransferRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.PrivacyRequestProcessRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.ReturnGrantTransferRequest;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.ArchiveGrantItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.ArchiveGrantTransferResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.PrivacyRequestDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.PrivacyRequestItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.PrivacyRequestProcessResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.ReturnGrantItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.ReturnGrantTransferResponse;
import com.sairo.be.domain.ops.service.OpsPrivacyOpsService;
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
@RequiresOperatorRole(OperatorRole.PRIVACY)
@Tag(name = "OPS", description = "개인정보 권리 요청 처리·반환·보관권한")
public class OpsPrivacyOpsController {

  private final OpsPrivacyOpsService service;

  public OpsPrivacyOpsController(OpsPrivacyOpsService service) {
    this.service = service;
  }

  @Operation(
      summary = "개인정보 권리 요청 큐",
      description = "status·requestType·officeId·assignedToMe로 거른다. 끝나지 않은 요청을 법정 기한 순으로 먼저 보인다.")
  @GetMapping("/privacy-requests")
  public PageResponse<PrivacyRequestItem> requests(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String requestType,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Boolean assignedToMe,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.requests(
        principal.operatorId(),
        status,
        requestType,
        officeId,
        assignedToMe,
        PageQuery.of(page, size));
  }

  @Operation(
      summary = "개인정보 권리 요청 상세",
      description = "범위·결정·완료 증빙·처리 이력과 지금 받을 수 있는 처리 단계를 보인다. 요청자 식별 값은 없다.")
  @GetMapping("/privacy-requests/{requestId}")
  public PrivacyRequestDetailResponse request(
      @PathVariable Long requestId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.request(principal.operatorId(), requestId);
  }

  @Operation(
      summary = "개인정보 권리 요청 처리 기록",
      description = "전달·협조요청·보완·승인·제한·거절·실행·완료를 기록한다. 보완 대기는 법정 기한을 멈추지 않는다.")
  @PostMapping("/privacy-requests/{requestId}/process")
  public PrivacyRequestProcessResponse process(
      @PathVariable Long requestId,
      @Valid @RequestBody PrivacyRequestProcessRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.process(principal.operatorId(), requestId, request);
  }

  @Operation(summary = "반환권한 현황", description = "officeId·activeOnly로 거르고 최근 발급 순으로 보인다.")
  @GetMapping("/return-grants")
  public PageResponse<ReturnGrantItem> returnGrants(
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Boolean activeOnly,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.returnGrants(officeId, activeOnly, PageQuery.of(page, size));
  }

  @Operation(summary = "보관권한 현황", description = "officeId·activeOnly로 거르고 최근 발급 순으로 보인다.")
  @GetMapping("/archive-grants")
  public PageResponse<ArchiveGrantItem> archiveGrants(
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Boolean activeOnly,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.archiveGrants(officeId, activeOnly, PageQuery.of(page, size));
  }

  @Operation(
      summary = "반환권한자 변경",
      description = "5분 재인증이 필요하다. 기존 권한 회수와 새 권한 발급(30일)을 한 트랜잭션으로 처리한다.")
  @PostMapping("/return-grants/{grantId}/transfer")
  public ReturnGrantTransferResponse transferReturnGrant(
      @PathVariable Long grantId,
      @Valid @RequestBody ReturnGrantTransferRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.transferReturnGrant(principal.operatorId(), grantId, request, session);
  }

  @Operation(
      summary = "보관권한자 변경",
      description = "5분 재인증이 필요하다. 기존 권한 종료와 새 권한 발급을 한 트랜잭션으로 처리하고 만료 시각은 이어받는다.")
  @PostMapping("/archive-grants/{grantId}/transfer")
  public ArchiveGrantTransferResponse transferArchiveGrant(
      @PathVariable Long grantId,
      @Valid @RequestBody ArchiveGrantTransferRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.transferArchiveGrant(principal.operatorId(), grantId, request, session);
  }
}
