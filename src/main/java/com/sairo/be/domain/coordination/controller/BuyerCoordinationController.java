package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.response.BuyerCreateResponse;
import com.sairo.be.domain.coordination.service.BuyerCoordinationService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/coordinations/{coordinationId}/buyers")
@RequiredArgsConstructor
@Tag(name = "VISIT", description = "임장 조율 건을 생성하고 진행 상태를 관리한다.")
public class BuyerCoordinationController {

  private final BuyerCoordinationService buyerCoordinationService;

  @Operation(
      summary = "구매희망자 조율 추가",
      description = "세입자 응답을 받은 임장 조율 건에 구매희망자를 추가하고 공개 응답 링크를 발급한다.")
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
      description = "세입자 응답 제출 전(TENANT_CHECKING)이라 구매희망자를 추가할 수 없다.",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BuyerCreateResponse create(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long coordinationId) {
    return buyerCoordinationService.addBuyer(
        principal.officeId(), principal.membershipId(), coordinationId);
  }
}
