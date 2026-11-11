package com.sairo.be.domain.contract.controller;

import com.sairo.be.domain.contract.dto.response.ContractDetailResponse;
import com.sairo.be.domain.contract.dto.response.ContractDuplicateExclusionResponse;
import com.sairo.be.domain.contract.dto.response.ContractListItem;
import com.sairo.be.domain.contract.dto.response.ContractRenewResponse;
import com.sairo.be.domain.contract.dto.response.ContractRevisionResponse;
import com.sairo.be.domain.contract.dto.response.ContractSettlementStatusResponse;
import com.sairo.be.domain.contract.dto.response.ContractStateResponse;
import com.sairo.be.domain.contract.dto.response.ContractStatusHistoryItem;
import com.sairo.be.domain.contract.service.ContractCommandService;
import com.sairo.be.domain.contract.service.ContractCorrectionService;
import com.sairo.be.domain.contract.service.ContractQueryService;
import com.sairo.be.domain.contract.service.ContractStatusService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/contracts")
@RequiresAccess(AccessPermission.AGENT_OR_OWNER)
@Tag(name = "CONTRACT", description = "계약")
public class ContractController {

  private final ContractQueryService queryService;
  private final ContractCommandService commandService;
  private final ContractCorrectionService correctionService;
  private final ContractStatusService statusService;

  public ContractController(
      ContractQueryService queryService,
      ContractCommandService commandService,
      ContractCorrectionService correctionService,
      ContractStatusService statusService) {
    this.queryService = queryService;
    this.commandService = commandService;
    this.correctionService = correctionService;
    this.statusService = statusService;
  }

  @Operation(
      summary = "계약 목록",
      description =
          "contractStatus(값 하나), propertyAddress, partyName, includeExcluded(기본 false),"
              + " leaseEndWithinDays(체결된 전세·월세 중 오늘부터 그 일수 안 만기)로 거른다.")
  @GetMapping
  public PageResponse<ContractListItem> list(
      @RequestParam(required = false) String contractStatus,
      @RequestParam(required = false) String propertyAddress,
      @RequestParam(required = false) String partyName,
      @RequestParam(required = false) Boolean includeExcluded,
      @RequestParam(required = false) Integer leaseEndWithinDays,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.list(
        principal,
        contractStatus,
        propertyAddress,
        partyName,
        includeExcluded,
        leaseEndWithinDays,
        PageQuery.of(page, size));
  }

  @Operation(summary = "계약 상세", description = "계약·임대기간·잔금 상태와 중복 제외 여부, 현재 당사자, 첨부 서류를 돌려준다.")
  @GetMapping("/{contractId}")
  public ContractDetailResponse detail(
      @PathVariable Long contractId, @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.detail(principal, contractId);
  }

  @Operation(
      summary = "계약 초안 수정",
      description = "보낸 필드만 바꾼다. 당사자 배열을 보내면 그쪽 당사자 전체를 바꾼다. If-Match에 현재 version을 보낸다.")
  @PatchMapping("/{contractId}/draft")
  public ContractStateResponse updateDraft(
      @PathVariable Long contractId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.updateDraft(principal, contractId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "계약 초안 삭제", description = "초안만 당사자와 함께 지운다.")
  @DeleteMapping("/{contractId}/draft")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteDraft(
      @PathVariable Long contractId, @AuthenticationPrincipal StaffPrincipal principal) {
    commandService.deleteDraft(principal, contractId);
  }

  @Operation(summary = "초안 체결 등록", description = "본문 없이 If-Match만 보낸다. 체결에 필요한 값을 같은 트랜잭션에서 검사한다.")
  @PostMapping("/{contractId}/sign")
  public ContractStateResponse sign(
      @PathVariable Long contractId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.sign(principal, contractId, IfMatchVersion.parse(ifMatch));
  }

  @Operation(
      summary = "체결 후 핵심정보 정정",
      description = "기준 버전, 사유, 효력시각과 현재 전체값을 보낸다. 이전 버전은 그대로 두고 새 버전을 더한다.")
  @PostMapping("/{contractId}/corrections")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractRevisionResponse correct(
      @PathVariable Long contractId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return correctionService.correct(principal, contractId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "계약 핵심정보 버전 이력", description = "revisionNo 오름차순이다. 초안은 빈 목록이다.")
  @GetMapping("/{contractId}/revisions")
  public PageResponse<ContractRevisionResponse> revisions(
      @PathVariable Long contractId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.revisions(principal, contractId, PageQuery.of(page, size));
  }

  @Operation(
      summary = "계약 상태 변경",
      description = "체결 후 종료·해제·취소·무효를 기록한다. 과거 오입력은 supersedesHistoryId로 대체한다.")
  @PatchMapping("/{contractId}/status")
  public ContractStateResponse changeStatus(
      @PathVariable Long contractId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return statusService.changeStatus(principal, contractId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "잔금 상태 기록", description = "잔금 트랙만 바꾼다. 계약 상태와 중개보수에는 영향이 없다.")
  @PatchMapping("/{contractId}/settlement-status")
  public ContractSettlementStatusResponse changeSettlementStatus(
      @PathVariable Long contractId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return statusService.changeSettlementStatus(
        principal, contractId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "재계약", description = "전세·월세만 된다. 직전 계약은 그대로 두고 새 체결 계약을 만든다.")
  @PostMapping("/{contractId}/renew")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractRenewResponse renew(
      @PathVariable Long contractId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.renew(principal, contractId, body);
  }

  @Operation(summary = "계약 상태 이력", description = "계약·임대기간·잔금 상태 변경과 정정 이력을 효력시각 순으로 돌려준다.")
  @GetMapping("/{contractId}/status-history")
  public PageResponse<ContractStatusHistoryItem> statusHistory(
      @PathVariable Long contractId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.statusHistory(principal, contractId, PageQuery.of(page, size));
  }

  @Operation(
      summary = "중복 계약 제외",
      description = "대표 전용이다. 계약과 서류, 체결 이력은 그대로 두고 업무 목록과 체결 집계에서만 뺀다.")
  @PostMapping("/{contractId}/duplicate-exclusion")
  @RequiresAccess(AccessPermission.OWNER)
  public ContractDuplicateExclusionResponse excludeDuplicate(
      @PathVariable Long contractId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return statusService.excludeDuplicate(
        principal, contractId, IfMatchVersion.parse(ifMatch), body);
  }
}
