package com.sairo.be.domain.migration.controller;

import com.sairo.be.domain.migration.dto.response.ExportAllowedTablesResponse;
import com.sairo.be.domain.migration.dto.response.ExportCreateResponse;
import com.sairo.be.domain.migration.dto.response.ExportListItem;
import com.sairo.be.domain.migration.dto.response.ExportResultResponse;
import com.sairo.be.domain.migration.service.ExportService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api")
@Tag(name = "EXPORT", description = "데이터 내보내기와 허용 표 정책")
public class ExportController {

  private final ExportService exportService;

  public ExportController(ExportService exportService) {
    this.exportService = exportService;
  }

  @Operation(
      summary = "데이터 내보내기 생성",
      description =
          "scope FULL은 대표만, 최근 10분 이내 재인증이 필요하다. 소속공인중개사는 허용된 표의 TABLES만 요청할 수 있다."
              + " 생성물은 24시간 뒤 만료된다.")
  @PostMapping("/exports")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public ExportCreateResponse create(
      @RequestBody JsonNode body,
      HttpSession session,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return exportService.create(principal, session, body);
  }

  @Operation(summary = "내보내기 이력 목록", description = "본인 생성분, 대표는 사무소 전체. 파일 삭제 전 건만 나온다.")
  @GetMapping("/exports")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PageResponse<ExportListItem> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return exportService.list(principal, status, page, size);
  }

  @Operation(summary = "내보내기 허용 표 목록 조회")
  @GetMapping("/exports/allowed-tables")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public ExportAllowedTablesResponse allowedTables(
      @AuthenticationPrincipal StaffPrincipal principal) {
    return exportService.allowedTables(principal);
  }

  @Operation(
      summary = "내보내기 상태와 다운로드 URL 조회",
      description = "다운로드 URL은 10분 유효하며 매 요청마다 권한을 다시 검사한다. 24시간이 지나면 410이다.")
  @GetMapping("/exports/{exportId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public ExportResultResponse get(
      @PathVariable Long exportId, @AuthenticationPrincipal StaffPrincipal principal) {
    return exportService.get(principal, exportId);
  }

  @Operation(summary = "소속공인중개사 허용 표 설정", description = "보낸 목록이 허용 목록 전체를 대체한다. 빈 배열이면 허용 없음이다.")
  @PutMapping("/offices/current/export-policy")
  @RequiresAccess(AccessPermission.OWNER)
  public ExportAllowedTablesResponse updatePolicy(
      @RequestBody JsonNode body, @AuthenticationPrincipal StaffPrincipal principal) {
    return exportService.updatePolicy(principal, body);
  }
}
