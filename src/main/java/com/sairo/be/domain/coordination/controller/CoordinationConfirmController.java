package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.CoordinationConfirmRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationConfirmResponse;
import com.sairo.be.domain.coordination.service.CoordinationConfirmationService;
import com.sairo.be.global.security.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
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
  @PostMapping
  public CoordinationConfirmResponse confirm(
      @AuthenticationPrincipal StaffPrincipal principal,
      @PathVariable Long coordinationId,
      @Valid @RequestBody CoordinationConfirmRequest request) {
    return coordinationConfirmationService.confirm(
        principal.officeId(), principal.membershipId(), coordinationId, request);
  }
}
