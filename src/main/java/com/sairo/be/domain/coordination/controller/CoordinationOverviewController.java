package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.ResponseRestartRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationDetailResponse;
import com.sairo.be.domain.coordination.dto.response.CoordinationListResponse;
import com.sairo.be.domain.coordination.dto.response.ResponseRestartResponse;
import com.sairo.be.domain.coordination.service.CoordinationQueryService;
import com.sairo.be.domain.coordination.service.ResponseRestartService;
import com.sairo.be.global.security.StaffPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/coordinations")
@RequiredArgsConstructor
public class CoordinationOverviewController {

  private final CoordinationQueryService coordinationQueryService;
  private final ResponseRestartService responseRestartService;

  @GetMapping
  public CoordinationListResponse list(@AuthenticationPrincipal StaffPrincipal principal) {
    return coordinationQueryService.list(principal.officeId());
  }

  @GetMapping("/{coordinationId}")
  public CoordinationDetailResponse getDetail(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long coordinationId) {
    return coordinationQueryService.getDetail(principal.officeId(), coordinationId);
  }

  @PostMapping("/{coordinationId}/responses/{responseId}/restart")
  public ResponseRestartResponse restart(
      @AuthenticationPrincipal StaffPrincipal principal,
      @PathVariable Long coordinationId,
      @PathVariable Long responseId,
      @Valid @RequestBody ResponseRestartRequest request) {
    return responseRestartService.restart(
        principal.officeId(), coordinationId, responseId, request);
  }
}
