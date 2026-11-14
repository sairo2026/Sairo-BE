package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.VisitRequestDetailResponse;
import com.sairo.be.domain.visit.dto.response.VisitRequestListResponse;
import com.sairo.be.domain.visit.dto.response.VisitRequestResponse;
import com.sairo.be.domain.visit.service.VisitRequestQueryService;
import com.sairo.be.domain.visit.service.VisitRequestSubmitService;
import com.sairo.be.domain.visit.service.VisitRequestWithdrawService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@Tag(name = "VISIT", description = "방문 신청 제출·철회·현황·알림")
public class VisitRequestController {

  private final VisitRequestSubmitService submitService;
  private final VisitRequestWithdrawService withdrawService;
  private final VisitRequestQueryService queryService;

  public VisitRequestController(
      VisitRequestSubmitService submitService,
      VisitRequestWithdrawService withdrawService,
      VisitRequestQueryService queryService) {
    this.submitService = submitService;
    this.withdrawService = withdrawService;
    this.queryService = queryService;
  }

  @Operation(
      summary = "방문 신청 제출",
      description = "후보 매물 하나에 방문 신청을 낸다. 대표와 소속공인중개사만 낼 수 있고 신청은 예약이나 점유가 아니다.")
  @PostMapping("/api/visit-coordinations/{coordinationId}/visit-requests")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public VisitRequestResponse submit(
      @PathVariable Long coordinationId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return submitService.submit(principal, coordinationId, body);
  }

  @Operation(summary = "방문 신청 현황 목록", description = "제출 시각 내림차순이며 status로 상태를 거를 수 있다.")
  @GetMapping("/api/visit-coordinations/{coordinationId}/visit-requests")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitRequestListResponse list(
      @PathVariable Long coordinationId,
      @RequestParam(required = false) String status,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.list(principal, coordinationId, status);
  }

  @Operation(summary = "방문 신청 상세", description = "상태 이력, 승인자 선택 기록, 재검증 기록을 함께 돌려준다.")
  @GetMapping("/api/visit-requests/{requestId}")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitRequestDetailResponse detail(
      @PathVariable Long requestId, @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.detail(principal, requestId);
  }

  @Operation(
      summary = "방문 신청 철회",
      description = "신청 대기 상태에서만 철회한다. If-Match는 보내면 신청 version과 비교한다.")
  @PostMapping("/api/visit-requests/{requestId}/withdraw")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public VisitRequestResponse withdraw(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    Long expected = ifMatch == null ? null : IfMatchVersion.parse(ifMatch);
    return withdrawService.withdraw(principal, requestId, expected, body);
  }
}
