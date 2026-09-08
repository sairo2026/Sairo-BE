package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.CoordinationCreateRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationCreateResponse;
import com.sairo.be.domain.coordination.service.CoordinationService;
import com.sairo.be.global.security.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
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
