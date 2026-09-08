package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.CoordinationConfirmRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationConfirmResponse;
import com.sairo.be.domain.coordination.service.CoordinationConfirmationService;
import com.sairo.be.global.security.StaffPrincipal;
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
public class CoordinationConfirmController {

  private final CoordinationConfirmationService coordinationConfirmationService;

  @PostMapping
  public CoordinationConfirmResponse confirm(
      @AuthenticationPrincipal StaffPrincipal principal,
      @PathVariable Long coordinationId,
      @Valid @RequestBody CoordinationConfirmRequest request) {
    return coordinationConfirmationService.confirm(
        principal.officeId(), principal.membershipId(), coordinationId, request);
  }
}
