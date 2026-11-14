package com.sairo.be.domain.migration.controller;

import com.sairo.be.domain.migration.dto.response.ImportBatchDetailResponse;
import com.sairo.be.domain.migration.dto.response.ImportBatchListResponse;
import com.sairo.be.domain.migration.dto.response.ImportBatchStatusResponse;
import com.sairo.be.domain.migration.dto.response.ImportCancelResponse;
import com.sairo.be.domain.migration.dto.response.ImportExecuteResponse;
import com.sairo.be.domain.migration.dto.response.ImportMappingConfirmResponse;
import com.sairo.be.domain.migration.dto.response.ImportMappingSuggestionResponse;
import com.sairo.be.domain.migration.dto.response.ImportPreviewResponse;
import com.sairo.be.domain.migration.service.ImportCancelService;
import com.sairo.be.domain.migration.service.ImportExecutionService;
import com.sairo.be.domain.migration.service.ImportMappingService;
import com.sairo.be.domain.migration.service.ImportPreviewService;
import com.sairo.be.domain.migration.service.ImportQueryService;
import com.sairo.be.domain.migration.service.ImportUploadService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/import-batches")
@Tag(name = "IMPORT", description = "데이터 이전 업로드·매핑·미리보기·실행·취소")
public class ImportBatchController {

  private final ImportUploadService uploadService;
  private final ImportMappingService mappingService;
  private final ImportPreviewService previewService;
  private final ImportExecutionService executionService;
  private final ImportCancelService cancelService;
  private final ImportQueryService queryService;

  public ImportBatchController(
      ImportUploadService uploadService,
      ImportMappingService mappingService,
      ImportPreviewService previewService,
      ImportExecutionService executionService,
      ImportCancelService cancelService,
      ImportQueryService queryService) {
    this.uploadService = uploadService;
    this.mappingService = mappingService;
    this.previewService = previewService;
    this.executionService = executionService;
    this.cancelService = cancelService;
    this.queryService = queryService;
  }

  @Operation(
      summary = "이전 파일 업로드",
      description = "XLS·XLSX·CSV, 최대 20MB, 시트당 1만 행·100열. 수식·매크로·외부 링크는 실행하지 않고 저장된 값만 읽는다.")
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @RequiresAccess(AccessPermission.OWNER)
  public ImportBatchStatusResponse upload(
      @RequestPart(value = "file", required = false) MultipartFile file,
      @RequestParam(required = false) String targetType,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return uploadService.upload(principal, file, targetType);
  }

  @Operation(summary = "이전 실행 이력과 이번 달 잔여 횟수", description = "최근 100건을 최신순으로 돌려준다.")
  @GetMapping
  @RequiresAccess(AccessPermission.OWNER)
  public ImportBatchListResponse list(@AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.list(principal);
  }

  @Operation(summary = "이전 실행 단위 상세", description = "진행 상태, 건수, 실패 사유, 취소 결과, 시트 목록을 돌려준다.")
  @GetMapping("/{batchId}")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportBatchDetailResponse detail(
      @PathVariable Long batchId, @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.detail(principal, batchId);
  }

  @Operation(
      summary = "이전 대상 시트 변경",
      description = "UPLOADED·MAPPING·PREVIEW에서만 가능하다. If-Match에 현재 version을 보낸다.")
  @PutMapping("/{batchId}/sheet")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportBatchStatusResponse selectSheet(
      @PathVariable Long batchId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return uploadService.selectSheet(principal, batchId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "AI 헤더 매핑 제안 조회", description = "규칙 기반 제안이며 참고용이다.")
  @GetMapping("/{batchId}/mapping-suggestion")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportMappingSuggestionResponse mappingSuggestion(
      @PathVariable Long batchId, @AuthenticationPrincipal StaffPrincipal principal) {
    return mappingService.suggestion(principal, batchId);
  }

  @Operation(summary = "매핑 확정", description = "확정할 때마다 새 매핑 버전이 쌓인다. If-Match에 현재 version을 보낸다.")
  @PutMapping("/{batchId}/mapping")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportMappingConfirmResponse confirmMapping(
      @PathVariable Long batchId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return mappingService.confirm(principal, batchId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "미리보기", description = "어떤 데이터도 생성하지 않는다. size는 25·50·100이다.")
  @GetMapping("/{batchId}/preview")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportPreviewResponse preview(
      @PathVariable Long batchId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return previewService.preview(principal, batchId, page, size);
  }

  @Operation(summary = "이전 실행", description = "신규 생성과 기존 연결만 지원한다. 비동기로 처리되며 If-Match는 보내면 검사한다.")
  @PostMapping("/{batchId}/execute")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportExecuteResponse execute(
      @PathVariable Long batchId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return executionService.execute(principal, batchId, optionalVersion(ifMatch));
  }

  @Operation(
      summary = "이전 취소",
      description = "완료 후 24시간 이내에만 가능하다. 외부연결·후속수정·법정보존 서류가 생긴 항목은 제외한다.")
  @PostMapping("/{batchId}/cancel")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportCancelResponse cancel(
      @PathVariable Long batchId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return cancelService.cancel(principal, batchId, optionalVersion(ifMatch));
  }

  @Operation(summary = "실패한 줄만 다시 처리", description = "같은 실행 묶음 안에서 처리하며 월 실행 횟수를 쓰지 않는다.")
  @PostMapping("/{batchId}/retry-failed")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportExecuteResponse retryFailed(
      @PathVariable Long batchId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return executionService.retryFailed(principal, batchId, optionalVersion(ifMatch));
  }

  private static Long optionalVersion(String ifMatch) {
    return ifMatch == null ? null : IfMatchVersion.parse(ifMatch);
  }
}
