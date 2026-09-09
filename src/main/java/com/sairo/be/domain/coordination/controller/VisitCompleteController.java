package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.response.VisitCompleteResponse;
import com.sairo.be.domain.coordination.service.VisitCompleteService;
import com.sairo.be.global.error.ErrorResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/coordinations/{coordinationId}/visit-complete")
@RequiredArgsConstructor
@Tag(name = "VISIT", description = "임장 조율 건을 생성하고 진행 상태를 관리한다.")
public class VisitCompleteController {

  private final VisitCompleteService visitCompleteService;

  @Operation(summary = "임장 완료 처리", description = "확정된 임장 일정을 완료 처리한다.")
  @ApiResponse(
      responseCode = "200",
      description = "완료 처리 성공",
      content = @Content(schema = @Schema(implementation = VisitCompleteResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "세션이 없거나 만료되어 로그인이 필요하다.",
      content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "세션은 유효하지만 CSRF 토큰이 없거나 올바르지 않다.",
      content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "다른 사무소 소유이거나 존재하지 않는 조율 건이다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "확정 완료(SCHEDULE_CONFIRMED) 상태가 아니라서 임장 완료로 전이할 수 없다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  public VisitCompleteResponse complete(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long coordinationId) {
    return visitCompleteService.complete(
        principal.officeId(), principal.membershipId(), coordinationId);
  }
}
