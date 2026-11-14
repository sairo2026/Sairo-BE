package com.sairo.be.domain.billing.controller;

import com.sairo.be.domain.billing.dto.response.ExportResponse;
import com.sairo.be.domain.billing.dto.response.GrantStatusResponse;
import com.sairo.be.domain.billing.service.ReturnGrantService;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/return-grants")
@Tag(name = "BILLING", description = "요금·구독·결제")
public class ReturnGrantController {

  private final ReturnGrantService returnGrantService;

  public ReturnGrantController(ReturnGrantService returnGrantService) {
    this.returnGrantService = returnGrantService;
  }

  @Operation(summary = "반환권한 상태 조회", description = "반환 전용 세션의 권한 행을 다시 검사한다.")
  @GetMapping("/current")
  @RequiresAccess(AccessPermission.RETURN_GRANT)
  public GrantStatusResponse current(@AuthenticationPrincipal GrantPrincipal principal) {
    return returnGrantService.current(principal);
  }

  @Operation(summary = "반환권한으로 자료 내보내기", description = "생성 직후 진행 중 상태이며 파일은 24시간 보관한다.")
  @PostMapping("/{grantId}/export")
  @RequiresAccess(AccessPermission.RETURN_GRANT)
  public ExportResponse createExport(
      @PathVariable Long grantId, @AuthenticationPrincipal GrantPrincipal principal) {
    return returnGrantService.createExport(principal, grantId);
  }

  @Operation(summary = "반환 내보내기 진행 상태 조회", description = "완료되면 10분 유효한 다운로드 주소를 준다.")
  @GetMapping("/{grantId}/exports/{exportId}")
  @RequiresAccess(AccessPermission.RETURN_GRANT)
  public ExportResponse exportStatus(
      @PathVariable Long grantId,
      @PathVariable Long exportId,
      @AuthenticationPrincipal GrantPrincipal principal) {
    return returnGrantService.exportStatus(principal, grantId, exportId);
  }
}
