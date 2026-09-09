package com.sairo.be.domain.property.controller;

import com.sairo.be.domain.property.dto.request.PropertyCreateRequest;
import com.sairo.be.domain.property.dto.request.PropertyUpdateRequest;
import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
import com.sairo.be.domain.property.dto.response.PropertyDuplicateCheckResponse;
import com.sairo.be.domain.property.dto.response.PropertyListResponse;
import com.sairo.be.domain.property.service.PropertyService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/properties")
@RequiredArgsConstructor
@Tag(name = "PROP", description = "사무소가 관리하는 매물을 등록·조회·수정한다.")
public class PropertyController {

  private final PropertyService propertyService;

  @Operation(summary = "매물 목록 조회", description = "현재 사무소가 등록한 매물 목록을 조회한다.")
  @ApiResponse(
      responseCode = "200",
      description = "조회 성공",
      content = @Content(schema = @Schema(implementation = PropertyListResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "세션이 없거나 만료되어 로그인이 필요하다.",
      content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
  @GetMapping
  public PropertyListResponse list(@AuthenticationPrincipal StaffPrincipal principal) {
    return propertyService.listProperties(principal.officeId());
  }

  @Operation(summary = "매물 주소 중복 확인", description = "등록 전 같은 주소의 매물이 이미 있는지 확인한다.")
  @ApiResponse(
      responseCode = "200",
      description = "조회 성공",
      content = @Content(schema = @Schema(implementation = PropertyDuplicateCheckResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "세션이 없거나 만료되어 로그인이 필요하다.",
      content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
  @GetMapping("/duplicate-check")
  public PropertyDuplicateCheckResponse duplicateCheck(
      @AuthenticationPrincipal StaffPrincipal principal, @RequestParam String address) {
    return propertyService.checkDuplicate(principal.officeId(), address);
  }

  @Operation(summary = "매물 등록", description = "새 매물을 등록한다.")
  @ApiResponse(
      responseCode = "400",
      description = "요청 본문이 비어 있거나 address·dealType 검증에 실패했다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "세션이 없거나 만료되어 로그인이 필요하다.",
      content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "세션은 유효하지만 CSRF 토큰이 없거나 올바르지 않다.",
      content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public PropertyDetailResponse create(
      @AuthenticationPrincipal StaffPrincipal principal,
      @Valid @RequestBody PropertyCreateRequest request) {
    return propertyService.registerProperty(principal.officeId(), request);
  }

  @Operation(summary = "매물 상세 조회", description = "매물 기본 정보를 조회한다.")
  @ApiResponse(
      responseCode = "200",
      description = "조회 성공",
      content = @Content(schema = @Schema(implementation = PropertyDetailResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "세션이 없거나 만료되어 로그인이 필요하다.",
      content = @Content(schema = @Schema(implementation = SecurityErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "다른 사무소 소유이거나 존재하지 않는 매물이다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{propertyId}")
  public PropertyDetailResponse detail(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long propertyId) {
    return propertyService.getProperty(principal.officeId(), propertyId);
  }

  @Operation(summary = "매물 정보 수정", description = "거래유형을 제외한 매물 기본 정보를 수정한다.")
  @ApiResponse(
      responseCode = "200",
      description = "수정 성공",
      content = @Content(schema = @Schema(implementation = PropertyDetailResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "요청 본문이 비어 있거나 address 검증에 실패했다.",
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
  @PatchMapping("/{propertyId}")
  public PropertyDetailResponse update(
      @AuthenticationPrincipal StaffPrincipal principal,
      @PathVariable Long propertyId,
      @Valid @RequestBody PropertyUpdateRequest request) {
    return propertyService.updateProperty(principal.officeId(), propertyId, request);
  }
}
