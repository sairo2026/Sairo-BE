package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.CoordinationConfirmRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationConfirmResponse;
import com.sairo.be.domain.coordination.service.CoordinationConfirmationService;
import com.sairo.be.global.error.ErrorResponse;
import com.sairo.be.global.error.SecurityErrorResponse;
import com.sairo.be.global.security.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/coordinations/{coordinationId}/confirm")
@RequiredArgsConstructor
@Tag(name = "VISIT", description = "임장 조율 건을 생성하고 진행 상태를 관리한다.")
public class CoordinationConfirmController {

  private final CoordinationConfirmationService coordinationConfirmationService;

  @Operation(summary = "임장 일정 최종 확정", description = "구매희망자 중 한 명과 확정 후보 시간을 선택해 임장 일정을 최종 확정한다.")
  @ApiResponse(
      responseCode = "200",
      description = "확정 성공",
      content = @Content(schema = @Schema(implementation = CoordinationConfirmResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "요청 본문이 비어 있거나 buyerResponseId·candidateTimeId 검증에 실패했다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
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
      description = "다른 사무소 소유이거나 존재하지 않는 조율 건, 그 조율 건에 속하지 않는 구매희망자 응답, 또는 존재하지 않는 후보 시간이다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description =
          "최종 확정 필요(FINAL_CONFIRMATION_REQUIRED) 상태가 아니거나 선택한 구매희망자 응답이 가능 시간 제출 상태가 아니다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "422",
      description = "선택한 구매희망자 응답이 실제로 선택하지 않은 candidateTimeId다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  public CoordinationConfirmResponse confirm(
      @AuthenticationPrincipal StaffPrincipal principal,
      @PathVariable Long coordinationId,
      @Valid @RequestBody CoordinationConfirmRequest request) {
    return coordinationConfirmationService.confirm(
        principal.officeId(), principal.membershipId(), coordinationId, request);
  }
}
