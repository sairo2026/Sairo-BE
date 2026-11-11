package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsTrialPriceRequests.BusinessCalendarUpdateRequest;
import com.sairo.be.domain.ops.dto.request.OpsTrialPriceRequests.PriceScheduleCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsTrialPriceRequests.TrialEventCreateRequest;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.BusinessCalendarItem;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.BusinessCalendarResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.PriceScheduleApproveResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.PriceScheduleCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.PriceScheduleListResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventApproveResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventListResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventPreviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventStopResponse;
import com.sairo.be.domain.ops.service.OpsTrialPriceService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops")
@RequiresAccess(AccessPermission.OPERATOR)
@Tag(name = "OPS", description = "요금표·체험 이벤트·영업일 달력")
public class OpsTrialPriceController {

  private final OpsTrialPriceService service;

  public OpsTrialPriceController(OpsTrialPriceService service) {
    this.service = service;
  }

  @Operation(
      summary = "요금표 버전 목록",
      description = "VAT 포함 요금표 버전을 효력 시각 최근 순으로 보인다. status·audience로 거른다.")
  @GetMapping("/price-schedules")
  @RequiresOperatorRole(OperatorRole.BILLING)
  public PriceScheduleListResponse prices(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String audience,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.prices(status, audience, PageQuery.of(page, size));
  }

  @Operation(
      summary = "요금표 새 버전 등록",
      description = "초안으로 등록한다. 버전 문자열은 효력 시각의 KST 연월과 대상으로 서버가 정하고 공지 시각은 시행 30일 전 이전이어야 한다.")
  @PostMapping("/price-schedules")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.BILLING)
  public PriceScheduleCreateResponse createPrice(
      @Valid @RequestBody PriceScheduleCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.createPrice(principal.operatorId(), request);
  }

  @Operation(summary = "요금표 승인", description = "작성자와 다른 결제담당이 승인한다. 효력 시각 전이면 예약, 지났으면 시행이다.")
  @PostMapping("/price-schedules/{scheduleId}/approve")
  @RequiresOperatorRole(OperatorRole.BILLING)
  public PriceScheduleApproveResponse approvePrice(
      @PathVariable Long scheduleId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.approvePrice(principal.operatorId(), scheduleId);
  }

  @Operation(
      summary = "체험 이벤트 목록",
      description = "기간·대상·승인 상태·무료 종료 방식을 시작 시각 최근 순으로 보인다. status·audience로 거른다.")
  @GetMapping("/trial-events")
  @RequiresOperatorRole(OperatorRole.OPS)
  public TrialEventListResponse events(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String audience,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.events(status, audience, PageQuery.of(page, size));
  }

  @Operation(summary = "체험 이벤트 생성·승인 요청", description = "승인 요청 상태(REVIEW)로 만든다.")
  @PostMapping("/trial-events")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.OPS)
  public TrialEventCreateResponse createEvent(
      @Valid @RequestBody TrialEventCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.createEvent(principal.operatorId(), request);
  }

  @Operation(
      summary = "체험 이벤트 대상 미리보기",
      description = "조건을 충족하는 사무소 수와 다른 이벤트로 이미 체험을 받은 사무소를 보인다.")
  @GetMapping("/trial-events/{eventId}/preview")
  @RequiresOperatorRole(OperatorRole.OPS)
  public TrialEventPreviewResponse previewEvent(@PathVariable Long eventId) {
    return service.previewEvent(eventId);
  }

  @Operation(
      summary = "체험 이벤트 승인",
      description = "작성자와 다른 운영담당이 승인한다. 시작 전이면 예약, 지났으면 활성이고 일반 이벤트의 가입 창이 겹치면 409다.")
  @PostMapping("/trial-events/{eventId}/approve")
  @RequiresOperatorRole(OperatorRole.OPS)
  public TrialEventApproveResponse approveEvent(
      @PathVariable Long eventId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.approveEvent(principal.operatorId(), eventId);
  }

  @Operation(summary = "체험 이벤트 중단", description = "예약·활성 이벤트를 종료한다. 이미 부여한 체험의 종료 시각은 바꾸지 않는다.")
  @PostMapping("/trial-events/{eventId}/stop")
  @RequiresOperatorRole(OperatorRole.OPS)
  public TrialEventStopResponse stopEvent(
      @PathVariable Long eventId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.stopEvent(principal.operatorId(), eventId);
  }

  @Operation(summary = "영업일 달력 조회", description = "KST 날짜별 영업일·휴무일 지정을 날짜 순으로 보인다. from·to로 거른다.")
  @GetMapping("/business-calendar")
  @RequiresOperatorRole(OperatorRole.OPS)
  public BusinessCalendarResponse calendar(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.calendar(from, to, PageQuery.of(page, size));
  }

  @Operation(summary = "영업일·휴무일 반영", description = "경로의 KST 날짜를 영업일 또는 휴무일로 지정하고 반영 운영자를 기록한다.")
  @PutMapping("/business-calendar/{date}")
  @RequiresOperatorRole(OperatorRole.OPS)
  public BusinessCalendarItem updateCalendar(
      @PathVariable String date,
      @Valid @RequestBody BusinessCalendarUpdateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.updateCalendar(principal.operatorId(), date, request);
  }
}
