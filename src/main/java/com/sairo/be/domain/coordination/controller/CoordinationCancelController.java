package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.response.CoordinationCancelResponse;
import com.sairo.be.domain.coordination.service.CoordinationCancelService;
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
@RequestMapping("/api/coordinations/{coordinationId}/cancel")
@RequiredArgsConstructor
@Tag(name = "VISIT", description = "임장 조율 건을 생성하고 진행 상태를 관리한다.")
public class CoordinationCancelController {

  private final CoordinationCancelService coordinationCancelService;

  @Operation(
      summary = "임장 조율 취소",
      description = "확정되지 않은 임장 조율 건을 취소 처리한다. 취소된 조율 건은 다시 활성화되지 않는다.")
  @ApiResponse(
      responseCode = "200",
      description = "취소 처리 성공",
      content = @Content(schema = @Schema(implementation = CoordinationCancelResponse.class)))
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
      description = "이미 확정·완료·취소된(SCHEDULE_CONFIRMED, VISIT_COMPLETED, CANCELLED) 조율 건이라 취소할 수 없다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  public CoordinationCancelResponse cancel(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long coordinationId) {
    return coordinationCancelService.cancel(
        principal.officeId(), principal.membershipId(), coordinationId);
  }
}
