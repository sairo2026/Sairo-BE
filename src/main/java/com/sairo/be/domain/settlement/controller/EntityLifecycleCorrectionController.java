package com.sairo.be.domain.settlement.controller;

import com.sairo.be.domain.settlement.dto.response.EntityLifecycleCorrectionResponse;
import com.sairo.be.domain.settlement.service.LifecycleCorrectionService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/entity-lifecycle-events")
@RequiresAccess(AccessPermission.AGENT_OR_OWNER)
@Tag(name = "SETTLE", description = "매물·고객 수명 사건 정정")
public class EntityLifecycleCorrectionController {

  private final LifecycleCorrectionService correctionService;

  public EntityLifecycleCorrectionController(LifecycleCorrectionService correctionService) {
    this.correctionService = correctionService;
  }

  @Operation(
      summary = "수명 사건 효력시각 정정",
      description = "원래 사건은 그대로 두고 새 효력시각과 사유를 가진 정정 사건을 더한다. 영향받은 달의 결산을 다시 계산한다.")
  @PostMapping("/{eventId}/corrections")
  @ResponseStatus(HttpStatus.CREATED)
  public EntityLifecycleCorrectionResponse correct(
      @PathVariable Long eventId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return correctionService.correct(principal, eventId, body);
  }
}
