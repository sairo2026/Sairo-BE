package com.sairo.be.domain.office.controller;

import com.sairo.be.domain.office.dto.request.OfficeChangeRequestCreateRequest;
import com.sairo.be.domain.office.dto.response.OfficeChangeRequestItemResponse;
import com.sairo.be.domain.office.dto.response.OfficeChangeRequestResponse;
import com.sairo.be.domain.office.dto.response.OfficeDetailResponse;
import com.sairo.be.domain.office.service.OfficeInfoService;
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
@Tag(name = "OFFICE", description = "사무소 기본정보와 정보 변경 신청")
public class OfficeController {

  private final OfficeInfoService officeInfoService;

  public OfficeController(OfficeInfoService officeInfoService) {
    this.officeInfoService = officeInfoService;
  }

  @Operation(summary = "사무소 기본정보 조회", description = "중개보조원에게는 대표 연락처를 주지 않는다.")
  @GetMapping("/offices/current")
  @RequiresAccess(AccessPermission.STAFF)
  public OfficeDetailResponse current(@AuthenticationPrincipal StaffPrincipal principal) {
    return officeInfoService.current(principal);
  }

  @Operation(summary = "대표 연락처 수정", description = "If-Match에 현재 version을 보낸다. 다른 필드는 받지 않는다.")
  @PatchMapping("/offices/current")
  @RequiresAccess(AccessPermission.OWNER)
  public OfficeDetailResponse updateCurrent(
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody Map<String, Object> body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return officeInfoService.updatePhone(principal, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(
      summary = "사무소 정보 변경 신청",
      description = "주소, 등록관계, 대표자 변경은 재인증 뒤 신청하고 승인 전까지 반영하지 않는다.")
  @PostMapping("/office-change-requests")
  @RequiresAccess(AccessPermission.OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public OfficeChangeRequestResponse createChangeRequest(
      @Valid @RequestBody OfficeChangeRequestCreateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    return officeInfoService.createChangeRequest(principal, session, request);
  }

  @Operation(summary = "사무소 정보 변경 신청 목록", description = "대표가 낸 신청을 최신순으로 돌려준다.")
  @GetMapping("/office-change-requests")
  @RequiresAccess(AccessPermission.OWNER)
  public PageResponse<OfficeChangeRequestItemResponse> listChangeRequests(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return officeInfoService.listChangeRequests(principal, status, PageQuery.of(page, size));
  }
}
