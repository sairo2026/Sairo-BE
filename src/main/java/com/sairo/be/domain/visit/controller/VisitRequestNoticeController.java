package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.VisitRequestNoticeListResponse;
import com.sairo.be.domain.visit.service.VisitRequestNoticeService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/visit-request-notices")
@Tag(name = "VISIT", description = "방문 신청 제출·철회·현황·알림")
public class VisitRequestNoticeController {

  private final VisitRequestNoticeService noticeService;

  public VisitRequestNoticeController(VisitRequestNoticeService noticeService) {
    this.noticeService = noticeService;
  }

  @Operation(summary = "방문 신청 알림 목록", description = "로그인한 직원이 수신자인 앱 내 알림만 최신순으로 돌려준다.")
  @GetMapping
  @RequiresAccess(AccessPermission.STAFF)
  public VisitRequestNoticeListResponse list(
      @RequestParam(required = false, defaultValue = "false") boolean unread,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return noticeService.list(principal, unread);
  }

  @Operation(summary = "방문 신청 알림 확인 처리", description = "이미 확인한 알림도 같은 결과로 처리한다.")
  @PostMapping("/{noticeId}/read")
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void read(@PathVariable Long noticeId, @AuthenticationPrincipal StaffPrincipal principal) {
    noticeService.markRead(principal, noticeId);
  }
}
