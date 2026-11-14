package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.ReturnVisitCancelResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.ReturnVisitResultResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitAccessRevocationResponse;
import com.sairo.be.domain.visit.service.ReturnGrantVisitService;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/return-grants/{grantId}")
@Tag(name = "VISIT", description = "재조율, 출입승인 철회 접수, 조율 취소와 복제")
public class ReturnGrantVisitController {

  private final ReturnGrantVisitService service;

  public ReturnGrantVisitController(ReturnGrantVisitService service) {
    this.service = service;
  }

  @Operation(
      summary = "반환권한으로 기존 방문 취소",
      description = "결과 기록 전이면 취소 상태가 되고 결과가 있으면 남은 예정 방문만 취소해 다시 집계한다. 활성 소속은 요구하지 않는다.")
  @PostMapping("/visit-coordinations/{coordinationId}/cancel")
  @RequiresAccess(AccessPermission.RETURN_GRANT)
  public ReturnVisitCancelResponse cancel(
      @PathVariable Long grantId,
      @PathVariable Long coordinationId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) JsonNode body,
      @AuthenticationPrincipal GrantPrincipal principal) {
    return service.cancel(principal, grantId, coordinationId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(
      summary = "반환권한으로 출입승인 철회 접수",
      description = "철회 사실을 출입승인에 기록한다. 같은 출입승인의 후속 접수는 최초 사실을 그대로 돌려준다.")
  @PostMapping("/visit-coordinations/{coordinationId}/access-revocations")
  @RequiresAccess(AccessPermission.RETURN_GRANT)
  public VisitAccessRevocationResponse revokeAccess(
      @PathVariable Long grantId,
      @PathVariable Long coordinationId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal GrantPrincipal principal) {
    return service.revokeAccess(
        principal, grantId, coordinationId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(
      summary = "반환권한으로 방문 결과 사후 기록",
      description = "기존 방문 항목의 실제 결과만 기록한다. 효력 시각은 미래일 수 없고 기존 결과 정정에는 사유가 필요하다.")
  @PatchMapping("/visit-schedule-items/{itemId}/result")
  @RequiresAccess(AccessPermission.RETURN_GRANT)
  public ReturnVisitResultResponse recordResult(
      @PathVariable Long grantId,
      @PathVariable Long itemId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal GrantPrincipal principal) {
    return service.recordResult(principal, grantId, itemId, IfMatchVersion.parse(ifMatch), body);
  }
}
