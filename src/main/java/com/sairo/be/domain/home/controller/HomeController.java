package com.sairo.be.domain.home.controller;

import com.sairo.be.domain.home.dto.response.HomeSummaryResponse;
import com.sairo.be.domain.home.service.HomeSummaryService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequiresAccess(AccessPermission.STAFF)
@Tag(name = "HOME", description = "홈 대시보드")
public class HomeController {

  private final HomeSummaryService summaryService;

  public HomeController(HomeSummaryService summaryService) {
    this.summaryService = summaryService;
  }

  @Operation(
      summary = "홈 요약",
      description =
          "오늘 방문, 진행 중 조율, 조율 상태별 건수, 미완료 업무, 미읽음 알림, 이번 달 캘린더를 돌려준다. 중개보조원은 본인 담당만 집계한다.")
  @GetMapping("/api/home")
  public HomeSummaryResponse summary(@AuthenticationPrincipal StaffPrincipal principal) {
    return summaryService.summary(principal);
  }
}
