package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.CoordinationCreateRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationCreateResponse;
import com.sairo.be.domain.coordination.service.CoordinationService;
import com.sairo.be.global.security.StaffPrincipal;
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
public class CoordinationController {

  private final CoordinationService coordinationService;

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
