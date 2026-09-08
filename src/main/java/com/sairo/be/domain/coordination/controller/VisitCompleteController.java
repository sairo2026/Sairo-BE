package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.response.VisitCompleteResponse;
import com.sairo.be.domain.coordination.service.VisitCompleteService;
import com.sairo.be.global.security.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
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
  @PostMapping
  public VisitCompleteResponse complete(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long coordinationId) {
    return visitCompleteService.complete(
        principal.officeId(), principal.membershipId(), coordinationId);
  }
}
