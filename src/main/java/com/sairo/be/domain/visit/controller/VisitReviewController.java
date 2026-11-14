package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.BrokerVisitTrustSummaryResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ReceivedReviewItem;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.VisitReviewDisputeResponse;
import com.sairo.be.domain.visit.service.VisitTrustService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequiresAccess(AccessPermission.AGENT_OR_OWNER)
@Tag(name = "VISIT", description = "방문 평가와 신뢰 요약")
public class VisitReviewController {

  private final VisitTrustService trustService;

  public VisitReviewController(VisitTrustService trustService) {
    this.trustService = trustService;
  }

  @Operation(
      summary = "본인 방문 신뢰 요약",
      description = "최근 12개월 완료 방문의 평가 응답 요약이다. 점수나 별점이 아니며 신청·선택·확정 가능 여부를 정하지 않는다.")
  @GetMapping("/api/visit-trust/me")
  public BrokerVisitTrustSummaryResponse me(@AuthenticationPrincipal StaffPrincipal principal) {
    return trustService.me(principal);
  }

  @Operation(
      summary = "받은 방문 평가 목록",
      description = "승인자 이름과 연락처는 없다. 대표는 scope=OFFICE로 사무소 구성원의 평가를 본다. 철회된 평가는 없다.")
  @GetMapping("/api/visit-reviews/received")
  public PageResponse<ReceivedReviewItem> received(
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return trustService.received(principal, scope, status, PageQuery.of(page, size));
  }

  @Operation(
      summary = "평가 이의 제기",
      description = "평가받은 구성원 본인이나 같은 사무소 대표가 마지막 입력 시각부터 30일 안에 한 번 낸다.")
  @PostMapping("/api/visit-reviews/{reviewId}/disputes")
  @ResponseStatus(HttpStatus.CREATED)
  public VisitReviewDisputeResponse dispute(
      @PathVariable Long reviewId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return trustService.dispute(principal, reviewId, body);
  }
}
