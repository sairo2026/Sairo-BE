package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.AssistantNoticeResponses.AssistantNoticeResponse;
import com.sairo.be.domain.visit.dto.response.AssistantNoticeResponses.AssistantNoticeSupervisionListResponse;
import com.sairo.be.domain.visit.service.AssistantNoticeService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/visit-assistant-notices")
@Tag(name = "VISIT", description = "보조원 고지 감독")
public class VisitAssistantNoticeController {

  private final AssistantNoticeService noticeService;

  public VisitAssistantNoticeController(AssistantNoticeService noticeService) {
    this.noticeService = noticeService;
  }

  @Operation(summary = "보조원 고지 감독 이력", description = "대표만 조회한다. 기간은 기록 날짜 기준이다.")
  @GetMapping
  @RequiresAccess(AccessPermission.OWNER)
  public AssistantNoticeSupervisionListResponse supervision(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Long assistantMembershipId,
      @RequestParam(required = false) Boolean confirmed,
      @RequestParam(required = false) Boolean recordedAfterTheFact,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return noticeService.supervision(
        principal,
        from,
        to,
        assistantMembershipId,
        confirmed,
        recordedAfterTheFact,
        PageQuery.of(page, size));
  }

  @Operation(summary = "보조원 고지 감독 확인", description = "대표와 소속공인중개사가 확인한다. 이미 확인한 기록은 다시 확인할 수 없다.")
  @PostMapping("/{noticeId}/confirm")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public AssistantNoticeResponse confirm(
      @PathVariable Long noticeId,
      @RequestBody(required = false) JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return noticeService.confirm(principal, noticeId, body);
  }
}
