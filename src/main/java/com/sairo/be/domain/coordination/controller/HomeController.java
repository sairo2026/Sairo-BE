package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.response.HomeSummaryResponse;
import com.sairo.be.domain.coordination.service.HomeSummaryService;
import com.sairo.be.global.error.SecurityErrorResponse;
import com.sairo.be.global.security.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
@Tag(name = "HOME", description = "사무소 홈 화면에 필요한 조율현황 요약 정보를 제공한다.")
public class HomeController {

  private final HomeSummaryService homeSummaryService;

  @Operation(summary = "홈 요약 조회", description = "홈 화면에 표시할 조율현황별 건수 요약을 조회한다.")
  @ApiResponse(
      responseCode = "200",
      description = "조회 성공",
      content = @Content(schema = @Schema(implementation = HomeSummaryResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "세션이 없거나 만료되어 로그인이 필요하다.",
      content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
  @GetMapping
  public HomeSummaryResponse getSummary(@AuthenticationPrincipal StaffPrincipal principal) {
    return homeSummaryService.getSummary(principal.officeId());
  }
}
