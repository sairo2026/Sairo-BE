package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.AssistantNoticeResponses.AssistantNoticeListResponse;
import com.sairo.be.domain.visit.dto.response.AssistantNoticeResponses.AssistantNoticeResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitScheduleItemResultHistoryResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitScheduleItemResultResponse;
import com.sairo.be.domain.visit.dto.response.VisitRequestResponse;
import com.sairo.be.domain.visit.service.AssistantNoticeService;
import com.sairo.be.domain.visit.service.ScheduleResultService;
import com.sairo.be.domain.visit.service.VisitRequestSubmitService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/visit-schedule-items/{itemId}")
@Tag(name = "VISIT", description = "방문 결과, 보조원 고지, 확정 방문 변경 신청")
public class VisitScheduleItemController {

  private final ScheduleResultService resultService;
  private final AssistantNoticeService noticeService;
  private final VisitRequestSubmitService submitService;

  public VisitScheduleItemController(
      ScheduleResultService resultService,
      AssistantNoticeService noticeService,
      VisitRequestSubmitService submitService) {
    this.resultService = resultService;
    this.noticeService = noticeService;
    this.submitService = submitService;
  }

  @Operation(
      summary = "방문 결과 기록·정정",
      description = "If-Match는 조율 건 version이다. 취소 결과는 확정 취소 절차를 따르고 정정은 이전 이력을 대체한다.")
  @PatchMapping("/result")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitScheduleItemResultResponse recordResult(
      @PathVariable Long itemId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return resultService.record(principal, itemId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "방문 결과 정정 이력", description = "최신순으로 돌려준다.")
  @GetMapping("/result-history")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitScheduleItemResultHistoryResponse resultHistory(
      @PathVariable Long itemId, @AuthenticationPrincipal StaffPrincipal principal) {
    return resultService.history(principal, itemId);
  }

  @Operation(summary = "보조원 신분 고지 기록", description = "중개보조원이 담당한 방문에서 담당 보조원이 고지 대상·방법·시각을 기록한다.")
  @PostMapping("/assistant-notice")
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.CREATED)
  public AssistantNoticeResponse recordNotice(
      @PathVariable Long itemId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return noticeService.record(principal, itemId, body);
  }

  @Operation(summary = "방문 항목별 보조원 고지 현황", description = "고지 기록과 필수 고지 대상의 기록 여부를 돌려준다.")
  @GetMapping("/assistant-notices")
  @RequiresAccess(AccessPermission.STAFF)
  public AssistantNoticeListResponse notices(
      @PathVariable Long itemId, @AuthenticationPrincipal StaffPrincipal principal) {
    return noticeService.list(principal, itemId);
  }

  @Operation(
      summary = "확정 방문 변경 신청",
      description = "기존 확정 방문을 대체 대상으로 지정한 신청이다. 승인자 선택과 재검증을 거치기 전까지 기존 방문은 유효하다.")
  @PostMapping("/change-requests")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public VisitRequestResponse requestChange(
      @PathVariable Long itemId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return submitService.submitChange(principal, itemId, body);
  }
}
