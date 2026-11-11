package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.ConfirmationResponses;
import com.sairo.be.domain.visit.dto.response.ScheduleConflictResponse;
import com.sairo.be.domain.visit.dto.response.VisitManagerUpdateResponse;
import com.sairo.be.domain.visit.dto.response.VisitScheduleUpdateResponse;
import com.sairo.be.domain.visit.service.ConfirmationService;
import com.sairo.be.domain.visit.service.ManagerChangeService;
import com.sairo.be.domain.visit.service.ScheduleComposeService;
import com.sairo.be.domain.visit.service.ScheduleConflictQueryService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/visit-coordinations/{coordinationId}")
@Tag(name = "VISIT", description = "방문 일정 구성, 담당자 변경, 확정 안내")
public class VisitScheduleController {

  private final ScheduleComposeService composeService;
  private final ScheduleConflictQueryService conflictService;
  private final ManagerChangeService managerService;
  private final ConfirmationService confirmationService;

  public VisitScheduleController(
      ScheduleComposeService composeService,
      ScheduleConflictQueryService conflictService,
      ManagerChangeService managerService,
      ConfirmationService confirmationService) {
    this.composeService = composeService;
    this.conflictService = conflictService;
    this.managerService = managerService;
    this.confirmationService = confirmationService;
  }

  @Operation(
      summary = "방문 일정 구성",
      description = "방문 순서와 소요시간을 저장하고 고객·승인자 조건·충돌을 반영한 시작 시각 후보를 돌려준다. 후보는 확정이 아니다.")
  @PutMapping("/schedule")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitScheduleUpdateResponse compose(
      @PathVariable Long coordinationId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return composeService.compose(principal, coordinationId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "일정 충돌 사전 확인", description = "담당자·고객·매물의 확정 방문과 이동 구간 충돌을 확인한다.")
  @GetMapping("/schedule/conflicts")
  @RequiresAccess(AccessPermission.STAFF)
  public ScheduleConflictResponse conflicts(
      @PathVariable Long coordinationId, @AuthenticationPrincipal StaffPrincipal principal) {
    return conflictService.conflicts(principal, coordinationId);
  }

  @Operation(summary = "조율 담당자 변경", description = "대표와 소속공인중개사만 호출한다. 기존 고객 링크는 폐기한다.")
  @PatchMapping("/manager")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitManagerUpdateResponse changeManager(
      @PathVariable Long coordinationId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return managerService.change(principal, coordinationId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "확정 안내문 조회", description = "확정된 일정의 고객 전체 동선과 승인자별 안내문을 돌려준다.")
  @GetMapping("/confirmation-messages")
  @RequiresAccess(AccessPermission.STAFF)
  public ConfirmationResponses.Messages messages(
      @PathVariable Long coordinationId, @AuthenticationPrincipal StaffPrincipal principal) {
    return confirmationService.messages(principal, coordinationId);
  }

  @Operation(summary = "확정 안내 전달 기록 추가", description = "실제 수신이나 법적 동의를 증명하지 않는 담당자의 전달 기록이다.")
  @PostMapping("/confirmation-deliveries")
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.CREATED)
  public ConfirmationResponses.DeliveryCreated recordDelivery(
      @PathVariable Long coordinationId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return confirmationService.record(principal, coordinationId, body);
  }

  @Operation(summary = "확정 안내 전달 기록 목록", description = "이전 버전 기록을 포함해 최신 버전 순으로 돌려준다.")
  @GetMapping("/confirmation-deliveries")
  @RequiresAccess(AccessPermission.STAFF)
  public ConfirmationResponses.DeliveryList deliveries(
      @PathVariable Long coordinationId, @AuthenticationPrincipal StaffPrincipal principal) {
    return confirmationService.list(principal, coordinationId);
  }
}
