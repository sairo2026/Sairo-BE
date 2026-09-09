package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.ResponseRestartRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationDetailResponse;
import com.sairo.be.domain.coordination.dto.response.CoordinationListResponse;
import com.sairo.be.domain.coordination.dto.response.ResponseRestartResponse;
import com.sairo.be.domain.coordination.service.CoordinationQueryService;
import com.sairo.be.domain.coordination.service.ResponseRestartService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/coordinations")
@RequiredArgsConstructor
@Tag(name = "VISIT", description = "임장 조율 건을 생성하고 진행 상태를 관리한다.")
public class CoordinationOverviewController {

  private final CoordinationQueryService coordinationQueryService;
  private final ResponseRestartService responseRestartService;

  @Operation(summary = "임장 조율 목록 조회", description = "현재 사무소의 임장 조율 건 목록을 조회한다.")
  @ApiResponse(
      responseCode = "200",
      description = "조회 성공",
      content = @Content(schema = @Schema(implementation = CoordinationListResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "세션이 없거나 만료되어 로그인이 필요하다.",
      content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
  @GetMapping
  public CoordinationListResponse list(@AuthenticationPrincipal StaffPrincipal principal) {
    return coordinationQueryService.list(principal.officeId());
  }

  @Operation(summary = "임장 조율 상세 조회", description = "임장 조율 건 하나의 상세 정보와 상태 이력을 조회한다.")
  @ApiResponse(
      responseCode = "200",
      description = "조회 성공",
      content = @Content(schema = @Schema(implementation = CoordinationDetailResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "세션이 없거나 만료되어 로그인이 필요하다.",
      content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "다른 사무소 소유이거나 존재하지 않는 조율 건이다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{coordinationId}")
  public CoordinationDetailResponse getDetail(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long coordinationId) {
    return coordinationQueryService.getDetail(principal.officeId(), coordinationId);
  }

  @Operation(summary = "고객 응답 재시작", description = "응답 불가 또는 만료된 고객 응답에 새 후보 시간을 지정해 다시 응답을 받는다.")
  @ApiResponse(
      responseCode = "200",
      description = "재시작 성공",
      content = @Content(schema = @Schema(implementation = ResponseRestartResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "candidateTimeIds에 중복된 값이 있거나 요청 본문 검증에 실패했다.",
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
      description = "다른 사무소 소유이거나 존재하지 않는 조율 건, 또는 그 조율 건에 속하지 않는 고객 응답이다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "가능한 시간 없음이나 만료 상태가 아니라서 재시작할 수 없는 고객 응답이다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "422",
      description = "제공된 후보 시간 범위 밖의 candidateTimeId를 선택했다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/{coordinationId}/responses/{responseId}/restart")
  public ResponseRestartResponse restart(
      @AuthenticationPrincipal StaffPrincipal principal,
      @PathVariable Long coordinationId,
      @PathVariable Long responseId,
      @Valid @RequestBody ResponseRestartRequest request) {
    return responseRestartService.restart(
        principal.officeId(), coordinationId, responseId, request);
  }
}
