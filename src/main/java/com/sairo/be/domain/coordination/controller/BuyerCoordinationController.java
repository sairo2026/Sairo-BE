package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.response.BuyerCreateResponse;
import com.sairo.be.domain.coordination.service.BuyerCoordinationService;
import com.sairo.be.global.security.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
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
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BuyerCreateResponse create(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long coordinationId) {
    return buyerCoordinationService.addBuyer(
        principal.officeId(), principal.membershipId(), coordinationId);
  }
}
