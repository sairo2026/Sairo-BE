package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.ApplyGrantResponses.ApplyGrantResponse;
import com.sairo.be.domain.visit.dto.response.ApplyGrantResponses.ApplyGrantRevokeResponse;
import com.sairo.be.domain.visit.dto.response.ApplyGrantResponses.CrossOfficeRequestItem;
import com.sairo.be.domain.visit.dto.response.ApplyGrantResponses.CrossOfficeScheduleItem;
import com.sairo.be.domain.visit.dto.response.ApplyGrantResponses.ReceivedApplyGrantItem;
import com.sairo.be.domain.visit.service.ApplyGrantService;
import com.sairo.be.domain.visit.service.CrossOfficeVisitQueryService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
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
@Tag(name = "VISIT", description = "다른 사무소 신청 허용")
public class ApplyGrantController {

  private static final String GRANTS =
      "/api/properties/{propertyId}/access-approvals/{approvalId}/apply-grants";

  private final ApplyGrantService grantService;
  private final CrossOfficeVisitQueryService crossOfficeService;

  public ApplyGrantController(
      ApplyGrantService grantService, CrossOfficeVisitQueryService crossOfficeService) {
    this.grantService = grantService;
    this.crossOfficeService = crossOfficeService;
  }

  @Operation(
      summary = "신청 허용 부여",
      description = "대표 승인자 출입승인에 다른 사무소 전체 또는 그 사무소의 중개사 한 명의 방문 신청을 허용한다.")
  @PostMapping(GRANTS)
  @ResponseStatus(HttpStatus.CREATED)
  public ApplyGrantResponse create(
      @PathVariable Long propertyId,
      @PathVariable Long approvalId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return grantService.create(principal, propertyId, approvalId, body);
  }

  @Operation(summary = "신청 허용 목록", description = "state로 거르며 최신 허용이 앞이다.")
  @GetMapping(GRANTS)
  public PageResponse<ApplyGrantResponse> list(
      @PathVariable Long propertyId,
      @PathVariable Long approvalId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return grantService.list(principal, propertyId, approvalId, state, PageQuery.of(page, size));
  }

  @Operation(summary = "신청 허용 회수", description = "그 허용을 근거로 한 신청 대기 신청은 기간 만료로 종결하고 확정 방문은 유지한다.")
  @PostMapping(GRANTS + "/{grantId}/revoke")
  public ApplyGrantRevokeResponse revoke(
      @PathVariable Long propertyId,
      @PathVariable Long approvalId,
      @PathVariable Long grantId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return grantService.revoke(principal, propertyId, approvalId, grantId, body);
  }

  @Operation(summary = "허용받은 매물 목록", description = "활성 허용의 매물만 돌려준다. 상세주소와 승인자 정보, 조건 값은 없다.")
  @GetMapping("/api/apply-grants/received")
  public PageResponse<ReceivedApplyGrantItem> received(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return grantService.received(principal, keyword, PageQuery.of(page, size));
  }

  @Operation(
      summary = "다른 사무소 신청 현황",
      description = "자기 매물에 다른 사무소가 허용 근거로 낸 신청을 읽기 전용으로 본다. 고객과 요청사항은 없다.")
  @GetMapping("/api/properties/{propertyId}/cross-office-requests")
  public PageResponse<CrossOfficeRequestItem> crossOfficeRequests(
      @PathVariable Long propertyId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return crossOfficeService.requests(principal, propertyId, status, PageQuery.of(page, size));
  }

  @Operation(
      summary = "다른 사무소 확정 방문 현황",
      description = "자기 매물의 다른 사무소 확정 방문을 시작 시각 순으로 읽기 전용으로 본다.")
  @GetMapping("/api/properties/{propertyId}/cross-office-schedules")
  public PageResponse<CrossOfficeScheduleItem> crossOfficeSchedules(
      @PathVariable Long propertyId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String result,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return crossOfficeService.schedules(
        principal,
        propertyId,
        date("from", from),
        date("to", to),
        result,
        PageQuery.of(page, size));
  }

  private static LocalDate date(String field, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(new FieldErrorItem(field, FieldErrorCode.INVALID_FORMAT, "날짜 형식이 아닙니다.")));
    }
  }
}
