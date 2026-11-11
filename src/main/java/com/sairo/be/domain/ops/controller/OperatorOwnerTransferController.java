package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.office.dto.response.OwnerTransferResponse;
import com.sairo.be.domain.ops.service.OperatorOwnerTransferService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops/owner-transfers")
@RequiresAccess(AccessPermission.OPERATOR)
@Tag(name = "OPS", description = "대표 이전 최종 승인")
public class OperatorOwnerTransferController {

  private final OperatorOwnerTransferService transferService;

  public OperatorOwnerTransferController(OperatorOwnerTransferService transferService) {
    this.transferService = transferService;
  }

  @Operation(summary = "대표 이전 최종 승인", description = "전제 조건을 다시 확인하고 기존 대표를 소속공인중개사로, 후보를 대표로 바꾼다.")
  @PostMapping("/{transferId}/approve")
  public OwnerTransferResponse approve(
      @PathVariable Long transferId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return transferService.approve(principal.operatorId(), transferId);
  }
}
