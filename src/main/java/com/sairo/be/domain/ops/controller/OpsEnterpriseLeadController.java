package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.OperatorEnterpriseLeadQuoteRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.OpsEnterpriseLeadAssignRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.OpsEnterpriseLeadCloseRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.OpsEnterpriseLeadContractRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.OpsEnterpriseLeadReopenRequest;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.EnterpriseLeadQuoteResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadAssignResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadCloseResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadConsultationStartResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadContractResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadItem;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadReopenResponse;
import com.sairo.be.domain.ops.service.OpsEnterpriseLeadService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops/enterprise-leads")
@RequiresAccess(AccessPermission.OPERATOR)
@RequiresOperatorRole(OperatorRole.SUPPORT)
@Tag(name = "OPS", description = "기업 도입 문의 처리")
public class OpsEnterpriseLeadController {

  private final OpsEnterpriseLeadService service;

  public OpsEnterpriseLeadController(OpsEnterpriseLeadService service) {
    this.service = service;
  }

  @Operation(
      summary = "기업 도입 문의 목록",
      description = "status·assignedOperatorId·unassigned로 거른다. 최근 접수 순이다.")
  @GetMapping
  public PageResponse<OpsEnterpriseLeadItem> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long assignedOperatorId,
      @RequestParam(required = false) Boolean unassigned,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.list(status, assignedOperatorId, unassigned, PageQuery.of(page, size));
  }

  @Operation(
      summary = "기업 도입 문의 상세",
      description = "견적 버전·진행 이력·지금 할 수 있는 처리를 보인다. 조회는 ENTERPRISE_LEAD_VIEWED로 감사 로그에 남긴다.")
  @GetMapping("/{leadId}")
  public OpsEnterpriseLeadDetailResponse detail(
      @PathVariable Long leadId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.detail(principal.operatorId(), leadId);
  }

  @Operation(
      summary = "담당 배정·재배정",
      description = "If-Match가 필요하다. 접수 문의는 배정으로 바뀌고 진행 중 문의는 상태를 유지한다.")
  @PostMapping("/{leadId}/assign")
  public OpsEnterpriseLeadAssignResponse assign(
      @PathVariable Long leadId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody OpsEnterpriseLeadAssignRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.assign(principal.operatorId(), leadId, ifMatch, request.assignedOperatorId());
  }

  @Operation(summary = "상담 시작", description = "배정 담당자 본인만 배정 상태의 문의를 상담으로 바꾼다. If-Match가 필요하다.")
  @PostMapping("/{leadId}/consultation-start")
  public OpsEnterpriseLeadConsultationStartResponse startConsultation(
      @PathVariable Long leadId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.startConsultation(principal.operatorId(), leadId, ifMatch);
  }

  @Operation(
      summary = "견적 발행·재발행",
      description = "배정 담당자만 상담·견적·거절 단계에서 발행한다. 유효기한은 14일이고 이전 버전은 대체된다.")
  @PostMapping("/{leadId}/quote")
  @ResponseStatus(HttpStatus.CREATED)
  public EnterpriseLeadQuoteResponse issueQuote(
      @PathVariable Long leadId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody OperatorEnterpriseLeadQuoteRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.issueQuote(principal.operatorId(), leadId, ifMatch, request);
  }

  @Operation(summary = "계약 체결 기록", description = "배정 담당자만 고객이 수락한 최신 견적을 근거로 기록한다. 계약은 종점이다.")
  @PostMapping("/{leadId}/contract")
  public OpsEnterpriseLeadContractResponse contract(
      @PathVariable Long leadId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody OpsEnterpriseLeadContractRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.contract(principal.operatorId(), leadId, ifMatch, request);
  }

  @Operation(summary = "계약 전 종료", description = "배정된 문의는 배정 담당자만, 접수 문의는 고객지원 운영자 누구나 종료한다.")
  @PostMapping("/{leadId}/close")
  public OpsEnterpriseLeadCloseResponse close(
      @PathVariable Long leadId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody OpsEnterpriseLeadCloseRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.close(principal.operatorId(), leadId, ifMatch, request);
  }

  @Operation(
      summary = "종료한 문의 재개",
      description = "종료 직전 단계로 돌아가되 견적·수락·거절 단계였으면 상담으로 돌아간다. 담당이 필요한데 없으면 409다.")
  @PostMapping("/{leadId}/reopen")
  public OpsEnterpriseLeadReopenResponse reopen(
      @PathVariable Long leadId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody OpsEnterpriseLeadReopenRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.reopen(principal.operatorId(), leadId, ifMatch, request);
  }
}
