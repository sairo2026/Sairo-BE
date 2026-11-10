package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.AccessApprovalHistoryItem;
import com.sairo.be.domain.visit.dto.response.AccessApprovalResponse;
import com.sairo.be.domain.visit.dto.response.ApproverConditionHistoryResponse;
import com.sairo.be.domain.visit.dto.response.ApproverConditionResponse;
import com.sairo.be.domain.visit.dto.response.ApproverPropertyLinkIssueResponse;
import com.sairo.be.domain.visit.dto.response.ApproverPropertyLinkStatusResponse;
import com.sairo.be.domain.visit.service.ApproverConditionService;
import com.sairo.be.domain.visit.service.ApproverPropertyLinkService;
import com.sairo.be.domain.visit.service.PropertyAccessApprovalService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/properties/{propertyId}/access-approvals")
@Tag(name = "VISIT", description = "출입승인, 승인자 링크, 승인자 조건")
public class PropertyAccessApprovalController {

  private final PropertyAccessApprovalService approvalService;
  private final ApproverPropertyLinkService linkService;
  private final ApproverConditionService conditionService;

  public PropertyAccessApprovalController(
      PropertyAccessApprovalService approvalService,
      ApproverPropertyLinkService linkService,
      ApproverConditionService conditionService) {
    this.approvalService = approvalService;
    this.linkService = linkService;
    this.conditionService = conditionService;
  }

  @Operation(
      summary = "출입승인 등록",
      description =
          "승인 역할을 생략하면 대표 승인자다. 같은 기간 유효한 대표 승인자가 있으면 409다. 연락처는 정규화해 해시를 함께 저장하고 해시는 응답에 없다.")
  @PostMapping
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.CREATED)
  public AccessApprovalResponse register(
      @PathVariable Long propertyId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return approvalService.register(principal, propertyId, body);
  }

  @Operation(summary = "출입승인 이력", description = "최근 등록한 출입승인이 앞이다.")
  @GetMapping
  @RequiresAccess(AccessPermission.STAFF)
  public PageResponse<AccessApprovalHistoryItem> history(
      @PathVariable Long propertyId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return approvalService.history(principal, propertyId, PageQuery.of(page, size));
  }

  @Operation(
      summary = "승인자 링크 발급·재발급",
      description = "대표와 소속공인중개사만 발급한다. 기존 활성 링크는 폐기하고 토큰 원문은 이 응답에서만 돌려준다.")
  @PostMapping("/{approvalId}/approver-property-link")
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.CREATED)
  public ApproverPropertyLinkIssueResponse issueLink(
      @PathVariable Long propertyId,
      @PathVariable Long approvalId,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return linkService.issue(principal, propertyId, approvalId);
  }

  @Operation(summary = "승인자 링크 상태", description = "토큰 원문은 돌려주지 않는다.")
  @GetMapping("/{approvalId}/approver-property-link")
  @RequiresAccess(AccessPermission.STAFF)
  public ApproverPropertyLinkStatusResponse linkStatus(
      @PathVariable Long propertyId,
      @PathVariable Long approvalId,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return linkService.status(principal, propertyId, approvalId);
  }

  @Operation(summary = "승인자 링크 폐기")
  @DeleteMapping("/{approvalId}/approver-property-link")
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeLink(
      @PathVariable Long propertyId,
      @PathVariable Long approvalId,
      @AuthenticationPrincipal StaffPrincipal principal) {
    linkService.revoke(principal, propertyId, approvalId);
  }

  @Operation(summary = "승인자 조건 버전 이력", description = "최신 버전이 앞이다.")
  @GetMapping("/{approvalId}/approver-conditions")
  @RequiresAccess(AccessPermission.STAFF)
  public ApproverConditionHistoryResponse conditionHistory(
      @PathVariable Long propertyId,
      @PathVariable Long approvalId,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return conditionService.history(principal, propertyId, approvalId);
  }

  @Operation(
      summary = "승인자 조건 대리 입력",
      description = "출처는 서버가 대리 입력으로 기록한다. 승인자 본인 입력이 활성이거나 공동 승인자 출입승인이면 409다.")
  @PutMapping("/{approvalId}/approver-conditions/proxy")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public ApproverConditionResponse proxyCondition(
      @PathVariable Long propertyId,
      @PathVariable Long approvalId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return conditionService.proxy(principal, propertyId, approvalId, body);
  }
}
