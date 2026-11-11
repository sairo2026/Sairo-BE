package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.ConfirmedScheduleResponses.ApproverConfirmedScheduleResponse;
import com.sairo.be.domain.visit.service.PublicConfirmedScheduleService;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@Tag(name = "RESPOND", description = "승인자 링크 응답")
public class PublicApproverScheduleController {

  private final PublicConfirmedScheduleService confirmedService;

  public PublicApproverScheduleController(PublicConfirmedScheduleService confirmedService) {
    this.confirmedService = confirmedService;
  }

  @Operation(
      summary = "승인자 확정 일정 조회",
      description = "본인 매물의 확정 일정을 읽기 전용으로 돌려준다. 고객 이름과 연락처는 없고 공동 승인자도 조회한다.")
  @GetMapping("/api/public/approver-property-links/{token}/confirmed-schedules")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<ApproverConfirmedScheduleResponse> confirmedSchedules(
      @PathVariable String token, HttpServletRequest request) {
    ApproverConfirmedScheduleResponse body =
        confirmedService.approver(token, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
