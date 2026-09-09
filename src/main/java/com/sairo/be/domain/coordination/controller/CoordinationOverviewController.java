package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.ResponseRestartRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationDetailResponse;
import com.sairo.be.domain.coordination.dto.response.CoordinationListResponse;
import com.sairo.be.domain.coordination.dto.response.ResponseRestartResponse;
import com.sairo.be.domain.coordination.service.CoordinationQueryService;
import com.sairo.be.domain.coordination.service.ResponseRestartService;
import com.sairo.be.global.security.StaffPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "VISIT", description = "임장 조율 건을 생성하고 진행 상태를 관리한다.")
public class CoordinationOverviewController {

  private final CoordinationQueryService coordinationQueryService;
  private final ResponseRestartService responseRestartService;

  @Operation(summary = "임장 조율 목록 조회", description = "현재 사무소의 임장 조율 건 목록을 조회한다.")
  @GetMapping
  public CoordinationListResponse list(@AuthenticationPrincipal StaffPrincipal principal) {
    return coordinationQueryService.list(principal.officeId());
  }

  @Operation(summary = "임장 조율 상세 조회", description = "임장 조율 건 하나의 상세 정보와 상태 이력을 조회한다.")
  @GetMapping("/{coordinationId}")
  public CoordinationDetailResponse getDetail(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long coordinationId) {
    return coordinationQueryService.getDetail(principal.officeId(), coordinationId);
  }

  @Operation(summary = "고객 응답 재시작", description = "응답 불가 또는 만료된 고객 응답에 새 후보 시간을 지정해 다시 응답을 받는다.")
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
