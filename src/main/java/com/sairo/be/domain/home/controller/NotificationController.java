package com.sairo.be.domain.home.controller;

import com.sairo.be.domain.home.dto.response.NotificationItem;
import com.sairo.be.domain.home.service.NotificationService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
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
@RequestMapping("/api/notifications")
@RequiresAccess(AccessPermission.STAFF)
@Tag(name = "HOME", description = "알림함")
public class NotificationController {

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @Operation(summary = "알림함 목록", description = "본인에게 온 알림만 최근 발생순으로 돌려준다.")
  @GetMapping
  public PageResponse<NotificationItem> list(
      @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return notificationService.list(principal, unreadOnly, PageQuery.of(page, size));
  }

  @Operation(summary = "알림 읽음 처리", description = "이미 읽은 알림도 같은 결과로 처리한다.")
  @PostMapping("/{notificationId}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void read(
      @PathVariable Long notificationId, @AuthenticationPrincipal StaffPrincipal principal) {
    notificationService.markRead(principal, notificationId);
  }
}
