package com.sairo.be.domain.billing.controller;

import com.sairo.be.domain.billing.dto.request.ArchiveGrantCreateRequest;
import com.sairo.be.domain.billing.dto.response.ArchiveGrantCreateResponse;
import com.sairo.be.domain.billing.dto.response.ExportResponse;
import com.sairo.be.domain.billing.dto.response.GrantStatusResponse;
import com.sairo.be.domain.billing.service.ArchiveGrantService;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/archive-grants")
@Tag(name = "BILLING", description = "요금·구독·결제")
public class ArchiveGrantController {

  private final ArchiveGrantService archiveGrantService;

  public ArchiveGrantController(ArchiveGrantService archiveGrantService) {
    this.archiveGrantService = archiveGrantService;
  }

  @Operation(summary = "보관권한 발급", description = "운영자가 별도 보관계약을 체결한 뒤 호출한다.")
  @PostMapping
  @RequiresAccess(AccessPermission.OPERATOR)
  @ResponseStatus(HttpStatus.CREATED)
  public ArchiveGrantCreateResponse create(@Valid @RequestBody ArchiveGrantCreateRequest request) {
    return archiveGrantService.create(request);
  }

  @Operation(summary = "보관권한 상태 조회", description = "보관 전용 세션의 권한 행을 다시 검사한다.")
  @GetMapping("/current")
  @RequiresAccess(AccessPermission.ARCHIVE_GRANT)
  public GrantStatusResponse current(@AuthenticationPrincipal GrantPrincipal principal) {
    return archiveGrantService.current(principal);
  }

  @Operation(
      summary = "법정보존문서 내보내기 생성",
      description = "보관 규칙이 법정 보존으로 지정한 계약 서류와 거래 기록의 내보내기 작업을 만든다.")
  @PostMapping("/{grantId}/documents/export")
  @RequiresAccess(AccessPermission.ARCHIVE_GRANT)
  @ResponseStatus(HttpStatus.CREATED)
  public ExportResponse createExport(
      @PathVariable Long grantId, @AuthenticationPrincipal GrantPrincipal principal) {
    return archiveGrantService.createExport(principal, grantId);
  }

  @Operation(summary = "법정보존문서 내보내기 진행 상태 조회", description = "완료되면 10분 유효한 다운로드 주소를 준다.")
  @GetMapping("/{grantId}/documents/exports/{exportId}")
  @RequiresAccess(AccessPermission.ARCHIVE_GRANT)
  public ExportResponse exportStatus(
      @PathVariable Long grantId,
      @PathVariable Long exportId,
      @AuthenticationPrincipal GrantPrincipal principal) {
    return archiveGrantService.exportStatus(principal, grantId, exportId);
  }
}
