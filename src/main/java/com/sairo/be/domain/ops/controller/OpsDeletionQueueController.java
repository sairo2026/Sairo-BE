package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.DeletionQueueCancelRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.DeletionQueueExcludeRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.DeletionQueueVerifyRequest;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueueCancelResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueueExcludeResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueueExecuteResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueueItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueuePreviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueueVerifyResponse;
import com.sairo.be.domain.ops.service.OpsDeletionQueueService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops/deletion-queue")
@RequiresAccess(AccessPermission.OPERATOR)
@RequiresOperatorRole(OperatorRole.PRIVACY)
@Tag(name = "OPS", description = "파기 큐·보존 예외")
public class OpsDeletionQueueController {

  private final OpsDeletionQueueService service;

  public OpsDeletionQueueController(OpsDeletionQueueService service) {
    this.service = service;
  }

  @Operation(
      summary = "파기 대상 큐",
      description =
          "queueId·state(PENDING, AWAITING_VERIFICATION, CANCELLED, SUCCESS, FAILED, EXCLUDED)·targetType·officeId로 거른다.")
  @GetMapping
  public PageResponse<DeletionQueueItem> list(
      @RequestParam(required = false) Long queueId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.list(queueId, state, targetType, officeId, PageQuery.of(page, size));
  }

  @Operation(summary = "파기 대상 단건", description = "목록 항목과 같은 값을 한 건만 돌려준다.")
  @GetMapping("/{queueId}")
  public DeletionQueueItem get(@PathVariable Long queueId) {
    return service.get(queueId);
  }

  @Operation(
      summary = "파기 실행 전 미리보기",
      description = "운영 저장소·검색 색인·파일·임시 내보내기 대상과 보존 사유를 보이고 산정한 건수를 저장한다.")
  @GetMapping("/{queueId}/preview")
  public DeletionQueuePreviewResponse preview(
      @PathVariable Long queueId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.preview(principal.operatorId(), queueId);
  }

  @Operation(summary = "보존 예외 처리", description = "근거·보류 만료 시각·재검토 예정일과 요청한 운영자와 다른 승인자가 필요하다.")
  @PostMapping("/{queueId}/exclude")
  public DeletionQueueExcludeResponse exclude(
      @PathVariable Long queueId,
      @Valid @RequestBody DeletionQueueExcludeRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.exclude(principal.operatorId(), queueId, request);
  }

  @Operation(
      summary = "파기 실행",
      description = "5분 재인증이 필요하다. 예정 시각이 지난 대기 건만 실행하고 검증 전에는 result가 비어 있다.")
  @PostMapping("/{queueId}/execute")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public DeletionQueueExecuteResponse execute(
      @PathVariable Long queueId,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.execute(principal.operatorId(), queueId, session);
  }

  @Operation(
      summary = "파기 결과 검증",
      description = "실행자와 다른 활성 개인정보담당이 네 저장소 결과를 확인한다. 모두 SUCCESS나 EXCLUDED일 때만 SUCCESS다.")
  @PostMapping("/{queueId}/verify")
  public DeletionQueueVerifyResponse verify(
      @PathVariable Long queueId,
      @Valid @RequestBody DeletionQueueVerifyRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.verify(principal.operatorId(), queueId, request);
  }

  @Operation(summary = "파기 취소", description = "실행 전 대기 건만 사유와 함께 취소한다.")
  @PostMapping("/{queueId}/cancel")
  public DeletionQueueCancelResponse cancel(
      @PathVariable Long queueId,
      @Valid @RequestBody DeletionQueueCancelRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.cancel(principal.operatorId(), queueId, request);
  }
}
