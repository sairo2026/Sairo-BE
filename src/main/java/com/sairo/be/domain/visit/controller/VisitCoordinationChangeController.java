package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitAccessRevocationResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitCoordinationCancelResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitCoordinationCloneResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitCoordinationRescheduleResponse;
import com.sairo.be.domain.visit.service.CoordinationDisruptionService;
import com.sairo.be.domain.visit.service.VisitCoordinationCommandService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/visit-coordinations/{coordinationId}")
@Tag(name = "VISIT", description = "재조율, 출입승인 철회 접수, 조율 취소와 복제")
public class VisitCoordinationChangeController {

  private final CoordinationDisruptionService disruptionService;
  private final VisitCoordinationCommandService commandService;

  public VisitCoordinationChangeController(
      CoordinationDisruptionService disruptionService,
      VisitCoordinationCommandService commandService) {
    this.disruptionService = disruptionService;
    this.commandService = commandService;
  }

  @Operation(
      summary = "재조율 필요 전환",
      description = "확정 완료 조율을 재조율 필요로 바꾸고 고객 링크를 폐기한다. 확정 방문은 옮기지 않고 영향 방문과 대안 후보를 돌려준다.")
  @PostMapping("/reschedule")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitCoordinationRescheduleResponse reschedule(
      @PathVariable Long coordinationId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return disruptionService.reschedule(
        principal, coordinationId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(
      summary = "출입승인 철회 접수",
      description = "철회 사실을 출입승인에 기록한다. 같은 출입승인의 후속 접수는 최초 사실을 그대로 돌려준다.")
  @PostMapping("/access-revocations")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitAccessRevocationResponse revokeAccess(
      @PathVariable Long coordinationId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return disruptionService.revokeAccess(
        principal, coordinationId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(
      summary = "조율 건 전체 취소",
      description = "결과 기록 전이면 취소 상태가 되고 결과가 있으면 남은 예정 방문만 취소해 다시 집계한다.")
  @PostMapping("/cancel")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitCoordinationCancelResponse cancel(
      @PathVariable Long coordinationId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return disruptionService.cancel(principal, coordinationId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "취소된 조율 복제", description = "새 기간의 조율 건을 만든다. 이전 링크와 출입승인 응답은 복제하지 않는다.")
  @PostMapping("/clone")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitCoordinationCloneResponse cloneCancelled(
      @PathVariable Long coordinationId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.cloneCancelled(principal, coordinationId, body);
  }
}
