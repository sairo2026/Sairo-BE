package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsImportSupportRequests.ImportSupportQuoteRequest;
import com.sairo.be.domain.ops.dto.request.OpsImportSupportRequests.ImportSupportRejectRequest;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportExecuteResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportItem;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportMappingResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportRejectResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportStartResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportSubmitReviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportSummaryResponse;
import com.sairo.be.domain.ops.service.OpsImportSupportService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops/import-support-requests")
@RequiresAccess(AccessPermission.OPERATOR)
@RequiresOperatorRole(OperatorRole.MIGRATION)
@Tag(name = "OPS", description = "유료 이전 지원 처리")
public class OpsImportSupportController {

  private final OpsImportSupportService service;

  public OpsImportSupportController(OpsImportSupportService service) {
    this.service = service;
  }

  @Operation(
      summary = "유료 이전 지원 신청 목록",
      description = "status·assignedToMe·officeId로 거른다. 끝나지 않은 신청을 오래된 순으로 먼저 보인다.")
  @GetMapping
  public PageResponse<ImportSupportItem> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Boolean assignedToMe,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.list(
        principal.operatorId(), status, assignedToMe, officeId, PageQuery.of(page, size));
  }

  @Operation(
      summary = "유료 이전 지원 상세",
      description = "현재 매핑·매핑 이력·미리보기 요약과 지금 할 수 있는 처리를 보인다. 고객 파일의 행 단위 원본 값은 없다.")
  @GetMapping("/{requestId}")
  public ImportSupportDetailResponse detail(
      @PathVariable Long requestId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.detail(principal.operatorId(), requestId);
  }

  @Operation(summary = "견적 제시", description = "신청·견적 상태에서만 받는다. If-Match가 필요하다.")
  @PostMapping("/{requestId}/quote")
  @ResponseStatus(HttpStatus.CREATED)
  public ImportSupportSummaryResponse quote(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody ImportSupportQuoteRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.quote(principal.operatorId(), requestId, ifMatch, request);
  }

  @Operation(
      summary = "견적 수락 전 신청 거절",
      description = "사유가 필수이고 If-Match가 필요하다. 거절 즉시 대표의 직접 매핑·실행 권한이 돌아온다.")
  @PostMapping("/{requestId}/reject")
  public ImportSupportRejectResponse reject(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody ImportSupportRejectRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.reject(principal.operatorId(), requestId, ifMatch, request);
  }

  @Operation(summary = "처리 시작", description = "결제가 끝난 신청만 시작하고 호출한 이전담당이 담당자가 된다. If-Match가 필요하다.")
  @PostMapping("/{requestId}/start")
  public ImportSupportStartResponse start(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.start(principal.operatorId(), requestId, ifMatch);
  }

  @Operation(
      summary = "이전담당 매핑 저장",
      description = "담당자가 처리 중 상태에서만 저장하고 저장마다 새 매핑 버전을 만든다. If-Match가 필요하다.")
  @PutMapping("/{requestId}/mapping")
  public ImportSupportMappingResponse mapping(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) JsonNode request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.saveMapping(principal.operatorId(), requestId, ifMatch, request);
  }

  @Operation(
      summary = "대표 검수 제출",
      description = "담당자가 최신 매핑 버전을 제출한다. 매핑이 없으면 409 MAPPING_REQUIRED다. If-Match가 필요하다.")
  @PostMapping("/{requestId}/submit-review")
  public ImportSupportSubmitReviewResponse submitReview(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.submitReview(principal.operatorId(), requestId, ifMatch);
  }

  @Operation(
      summary = "승인 매핑으로 이전 실행",
      description = "대표가 승인한 매핑 버전으로 한 번만 실행을 시작한다. 실행은 비동기다. If-Match가 필요하다.")
  @PostMapping("/{requestId}/execute")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ImportSupportExecuteResponse execute(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.execute(principal.operatorId(), requestId, ifMatch);
  }

  @Operation(summary = "결과 납품 확인", description = "승인 상태이고 이전 실행이 완료된 뒤 담당자만 확인한다. If-Match가 필요하다.")
  @PostMapping("/{requestId}/deliver")
  public ImportSupportSummaryResponse deliver(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.deliver(principal.operatorId(), requestId, ifMatch);
  }
}
