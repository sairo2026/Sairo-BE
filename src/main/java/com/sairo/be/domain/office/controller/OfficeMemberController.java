package com.sairo.be.domain.office.controller;

import com.sairo.be.domain.office.dto.request.EmploymentRosterCreateRequest;
import com.sairo.be.domain.office.dto.request.OwnerTransferCreateRequest;
import com.sairo.be.domain.office.dto.request.OwnerTransferRejectRequest;
import com.sairo.be.domain.office.dto.response.EmploymentRosterItemResponse;
import com.sairo.be.domain.office.dto.response.EmploymentRosterListResponse;
import com.sairo.be.domain.office.dto.response.OfficeMembershipListItemResponse;
import com.sairo.be.domain.office.dto.response.OwnerTransferResponse;
import com.sairo.be.domain.office.service.EmploymentRosterService;
import com.sairo.be.domain.office.service.OfficeMemberListService;
import com.sairo.be.domain.office.service.OwnerTransferService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

@Profile("!migrate")
@RestController
@RequestMapping("/api")
@Tag(name = "MEMBER", description = "구성원 목록, 고용명부, 대표 이전")
public class OfficeMemberController {

  private final OfficeMemberListService listService;
  private final EmploymentRosterService rosterService;
  private final OwnerTransferService transferService;

  public OfficeMemberController(
      OfficeMemberListService listService,
      EmploymentRosterService rosterService,
      OwnerTransferService transferService) {
    this.listService = listService;
    this.rosterService = rosterService;
    this.transferService = transferService;
  }

  @Operation(
      summary = "구성원 목록",
      description = "status를 생략하면 승인된 구성원만 준다. 승인 대기와 접근정지 목록은 대표만 볼 수 있다.")
  @GetMapping("/offices/current/memberships")
  @RequiresAccess(AccessPermission.STAFF)
  public PageResponse<OfficeMembershipListItemResponse> memberships(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return listService.list(principal, status, PageQuery.of(page, size));
  }

  @Operation(summary = "고용명부 등록", description = "중개보조원 행은 한도를 넘어도 막지 않고 목록의 quota.exceeded로 알린다.")
  @PostMapping("/offices/current/employment-roster")
  @RequiresAccess(AccessPermission.OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public EmploymentRosterItemResponse createRoster(
      @Valid @RequestBody EmploymentRosterCreateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return rosterService.create(principal, request);
  }

  @Operation(summary = "고용명부 목록과 중개보조원 한도")
  @GetMapping("/offices/current/employment-roster")
  @RequiresAccess(AccessPermission.OWNER)
  public EmploymentRosterListResponse roster(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return rosterService.list(principal, PageQuery.of(page, size));
  }

  @Operation(summary = "고용명부 항목 수정", description = "If-Match에 사무소 집계의 현재 version을 보낸다.")
  @PatchMapping("/offices/current/employment-roster/{rosterId}")
  @RequiresAccess(AccessPermission.OWNER)
  public EmploymentRosterItemResponse updateRoster(
      @PathVariable Long rosterId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody Map<String, Object> body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return rosterService.update(principal, rosterId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "대표 이전 시작", description = "재인증이 필요하고 수락 기한은 7일이다.")
  @PostMapping("/offices/current/owner-transfer")
  @RequiresAccess(AccessPermission.OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public OwnerTransferResponse createTransfer(
      @Valid @RequestBody OwnerTransferCreateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    return transferService.create(principal, session, request);
  }

  @Operation(summary = "진행 중인 대표 이전 조회", description = "대표와 후보 당사자만 볼 수 있고 진행 중인 이전이 없으면 404다.")
  @GetMapping("/offices/current/owner-transfer")
  @RequiresAccess(AccessPermission.STAFF)
  public OwnerTransferResponse currentTransfer(@AuthenticationPrincipal StaffPrincipal principal) {
    return transferService.current(principal);
  }

  @Operation(summary = "대표 이전 상태 조회", description = "당사자만 볼 수 있다.")
  @GetMapping("/owner-transfers/{transferId}")
  @RequiresAccess(AccessPermission.STAFF)
  public OwnerTransferResponse transfer(
      @PathVariable Long transferId, @AuthenticationPrincipal StaffPrincipal principal) {
    return transferService.get(principal, transferId);
  }

  @Operation(summary = "신규 대표 후보의 수락")
  @PostMapping("/owner-transfers/{transferId}/accept")
  @RequiresAccess(AccessPermission.STAFF)
  public OwnerTransferResponse accept(
      @PathVariable Long transferId, @AuthenticationPrincipal StaffPrincipal principal) {
    return transferService.accept(principal, transferId);
  }

  @Operation(summary = "신규 대표 후보의 거절")
  @PostMapping("/owner-transfers/{transferId}/reject")
  @RequiresAccess(AccessPermission.STAFF)
  public OwnerTransferResponse reject(
      @PathVariable Long transferId,
      @Valid @RequestBody(required = false) OwnerTransferRejectRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return transferService.reject(principal, transferId, request == null ? null : request.reason());
  }

  @Operation(summary = "대표 이전 철회")
  @PostMapping("/owner-transfers/{transferId}/withdraw")
  @RequiresAccess(AccessPermission.OWNER)
  public OwnerTransferResponse withdraw(
      @PathVariable Long transferId, @AuthenticationPrincipal StaffPrincipal principal) {
    return transferService.withdraw(principal, transferId);
  }
}
