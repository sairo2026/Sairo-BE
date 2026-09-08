package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.response.BuyerCreateResponse;
import com.sairo.be.domain.coordination.service.BuyerCoordinationService;
import com.sairo.be.global.security.StaffPrincipal;
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
public class BuyerCoordinationController {

  private final BuyerCoordinationService buyerCoordinationService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BuyerCreateResponse create(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long coordinationId) {
    return buyerCoordinationService.addBuyer(
        principal.officeId(), principal.membershipId(), coordinationId);
  }
}
