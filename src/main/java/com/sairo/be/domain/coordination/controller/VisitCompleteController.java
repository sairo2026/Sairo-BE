package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.response.VisitCompleteResponse;
import com.sairo.be.domain.coordination.service.VisitCompleteService;
import com.sairo.be.global.security.StaffPrincipal;
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
public class VisitCompleteController {

  private final VisitCompleteService visitCompleteService;

  @PostMapping
  public VisitCompleteResponse complete(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long coordinationId) {
    return visitCompleteService.complete(
        principal.officeId(), principal.membershipId(), coordinationId);
  }
}
