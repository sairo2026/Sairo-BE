package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.OpsDisputeItem;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.OpsVisitReviewResponse;
import com.sairo.be.domain.visit.service.OpsVisitReviewService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequiresAccess(AccessPermission.OPERATOR)
@RequiresOperatorRole(OperatorRole.SUPPORT)
@Tag(name = "OPS", description = "방문 평가 이의 처리")
public class OpsVisitReviewController {

  private final OpsVisitReviewService opsService;

  public OpsVisitReviewController(OpsVisitReviewService opsService) {
    this.opsService = opsService;
  }

  @Operation(
      summary = "평가 이의 목록",
      description = "접수 시각 오름차순이다. status를 생략하면 처리 중 이의만 돌려준다. 승인자 이름·연락처·매물 주소는 없다.")
  @GetMapping("/api/ops/visit-review-disputes")
  public PageResponse<OpsDisputeItem> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return opsService.list(status, PageQuery.of(page, size));
  }

  @Operation(summary = "평가 이의 결정", description = "평가 유지 또는 평가 삭제를 결정한다. 결정 사유 메모가 필요하다.")
  @PostMapping("/api/ops/visit-review-disputes/{disputeId}/decision")
  public OpsVisitReviewResponse decide(
      @PathVariable Long disputeId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return opsService.decide(principal.operatorId(), disputeId, body);
  }

  @Operation(
      summary = "평가 직권 삭제",
      description = "법적 요청, 개인정보 요청, 운영 재량 가운데 사유 코드를 받는다. 열린 이의는 함께 닫는다.")
  @PostMapping("/api/ops/visit-reviews/{reviewId}/remove")
  public OpsVisitReviewResponse remove(
      @PathVariable Long reviewId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return opsService.remove(principal.operatorId(), reviewId, body);
  }
}
