package com.sairo.be.domain.customer.controller;

import com.sairo.be.domain.customer.dto.response.CustomerAssistantViewResponse;
import com.sairo.be.domain.customer.dto.response.CustomerCreateResponse;
import com.sairo.be.domain.customer.dto.response.CustomerDetailResponse;
import com.sairo.be.domain.customer.dto.response.CustomerListItemResponse;
import com.sairo.be.domain.customer.dto.response.DuplicateCustomerCheckResponse;
import com.sairo.be.domain.customer.service.CustomerCommandService;
import com.sairo.be.domain.customer.service.CustomerQueryService;
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
@RequestMapping("/api/customers")
@Tag(name = "CUSTOMER", description = "고객 등록·조회·수정·보관")
public class CustomerController {

  private final CustomerQueryService queryService;
  private final CustomerCommandService commandService;

  public CustomerController(
      CustomerQueryService queryService, CustomerCommandService commandService) {
    this.queryService = queryService;
    this.commandService = commandService;
  }

  @Operation(summary = "고객 목록", description = "병합된 고객은 제외하고 병합 이력의 원래 이름·연락처도 검색한다.")
  @GetMapping
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PageResponse<CustomerListItemResponse> list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String role,
      @RequestParam(required = false) Boolean archived,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.list(principal, keyword, role, archived, PageQuery.of(page, size));
  }

  @Operation(summary = "고객 등록", description = "수집 근거·목적·보유기한이 필수이며 같은 이름·번호가 있어도 자동 병합하지 않는다.")
  @PostMapping
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public CustomerCreateResponse create(
      @RequestBody JsonNode body, @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.create(principal, body);
  }

  @Operation(summary = "같은 이름·전화번호 고객 후보 조회")
  @GetMapping("/duplicate-candidates")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public DuplicateCustomerCheckResponse duplicateCandidates(
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String phone,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.duplicateCandidates(principal, name, phone);
  }

  @Operation(
      summary = "고객 상세",
      description = "병합된 고객은 200으로 status=MERGED와 mergedIntoCustomerId를 돌려준다.")
  @GetMapping("/{customerId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public CustomerDetailResponse detail(
      @PathVariable Long customerId, @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.detail(principal, customerId);
  }

  @Operation(summary = "고객 기본정보 수정", description = "If-Match에 현재 고객 version을 보낸다.")
  @PatchMapping("/{customerId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public CustomerDetailResponse update(
      @PathVariable Long customerId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.update(principal, customerId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "고객 삭제 또는 보관", description = "참조가 없으면 물리 삭제하고 있으면 보관한다. If-Match는 보내면 검사한다.")
  @DeleteMapping("/{customerId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable Long customerId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal StaffPrincipal principal) {
    commandService.delete(
        principal, customerId, ifMatch == null ? null : IfMatchVersion.parse(ifMatch));
  }

  @Operation(summary = "보관 고객 복원", description = "If-Match는 보내면 검사한다.")
  @PostMapping("/{customerId}/restore")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public CustomerDetailResponse restore(
      @PathVariable Long customerId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.restore(
        principal, customerId, ifMatch == null ? null : IfMatchVersion.parse(ifMatch));
  }

  @Operation(summary = "현장안내 보조 조회", description = "이름과 연락처만 돌려준다.")
  @GetMapping("/{customerId}/assistant-view")
  @RequiresAccess(AccessPermission.STAFF)
  public CustomerAssistantViewResponse assistantView(
      @PathVariable Long customerId, @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.assistantView(principal, customerId);
  }
}
