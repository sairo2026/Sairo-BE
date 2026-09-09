package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.CoordinationCreateRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationCreateResponse;
import com.sairo.be.domain.coordination.service.CoordinationService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/properties/{propertyId}/coordinations")
@RequiredArgsConstructor
@Tag(name = "VISIT", description = "임장 조율 건을 생성하고 진행 상태를 관리한다.")
public class CoordinationController {

  private final CoordinationService coordinationService;

  @Operation(summary = "임장 조율 생성", description = "매물에 대한 임장 조율 건을 생성하고 세입자에게 전달할 공개 응답 링크를 발급한다.")
  @ApiResponse(
      responseCode = "400",
      description = "candidateTimes에 중복된 시작 시각이 있거나 요청 본문 검증에 실패했다.",
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
      description = "다른 사무소 소유이거나 존재하지 않는 매물이다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CoordinationCreateResponse create(
      @AuthenticationPrincipal StaffPrincipal principal,
      @PathVariable Long propertyId,
      @Valid @RequestBody CoordinationCreateRequest request) {
    return coordinationService.createForTenant(
        principal.officeId(), principal.membershipId(), propertyId, request);
  }
}
