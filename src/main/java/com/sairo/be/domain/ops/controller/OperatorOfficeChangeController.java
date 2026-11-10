package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsOfficeChangeRejectRequest;
import com.sairo.be.domain.ops.dto.response.OpsOfficeChangeItemResponse;
import com.sairo.be.domain.ops.dto.response.OpsOfficeChangeResultResponse;
import com.sairo.be.domain.ops.service.OperatorOfficeChangeService;
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
@RequestMapping("/api/ops/office-change-requests")
@RequiresAccess(AccessPermission.OPERATOR)
@RequiresOperatorRole(OperatorRole.REVIEW)
@Tag(name = "OPS", description = "사무소 정보 변경 심사")
public class OperatorOfficeChangeController {

  private final OperatorOfficeChangeService changeService;

  public OperatorOfficeChangeController(OperatorOfficeChangeService changeService) {
    this.changeService = changeService;
  }

  @Operation(summary = "사무소 정보 변경 신청 목록", description = "status를 생략하면 심사 중 신청만 돌려준다.")
  @GetMapping
  public PageResponse<OpsOfficeChangeItemResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return changeService.list(status, PageQuery.of(page, size));
  }

  @Operation(summary = "변경 승인", description = "신청 값을 사무소 정보에 반영한다.")
  @PostMapping("/{requestId}/approve")
  public OpsOfficeChangeResultResponse approve(
      @PathVariable Long requestId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return changeService.approve(principal.operatorId(), requestId);
  }

  @Operation(summary = "변경 반려", description = "고객에게 공개되는 사유가 필요하다.")
  @PostMapping("/{requestId}/reject")
  public OpsOfficeChangeResultResponse reject(
      @PathVariable Long requestId,
      @Valid @RequestBody OpsOfficeChangeRejectRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return changeService.reject(principal.operatorId(), requestId, request);
  }
}
