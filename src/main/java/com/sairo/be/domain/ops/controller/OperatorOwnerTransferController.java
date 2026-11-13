package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.office.dto.response.OwnerTransferResponse;
import com.sairo.be.domain.ops.dto.response.OpsOwnerTransferListResponse;
import com.sairo.be.domain.ops.service.OperatorOwnerTransferService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops/owner-transfers")
@RequiresAccess(AccessPermission.OPERATOR)
@Tag(name = "OPS", description = "대표 이전 목록과 최종 승인")
public class OperatorOwnerTransferController {

  private final OperatorOwnerTransferService transferService;

  public OperatorOwnerTransferController(OperatorOwnerTransferService transferService) {
    this.transferService = transferService;
  }

  @Operation(
      summary = "대표 이전 목록",
      description = "status(쉼표로 여러 값)·officeId로 거른다. status를 생략하면 운영자 최종 승인 대기 건만 수락 순으로 보인다.")
  @GetMapping
  @RequiresOperatorRole(OperatorRole.REVIEW)
  public OpsOwnerTransferListResponse list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return transferService.list(status, officeId, PageQuery.of(page, size));
  }

  @Operation(summary = "대표 이전 최종 승인", description = "전제 조건을 다시 확인하고 기존 대표를 소속공인중개사로, 후보를 대표로 바꾼다.")
  @PostMapping("/{transferId}/approve")
  public OwnerTransferResponse approve(
      @PathVariable Long transferId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return transferService.approve(principal.operatorId(), transferId);
  }
}
