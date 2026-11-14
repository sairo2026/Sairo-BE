package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.ApproverConditionImpactPreviewResponse;
import com.sairo.be.domain.visit.dto.response.ApproverConditionResponse;
import com.sairo.be.domain.visit.service.ApproverConditionService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/public/approver-property-links/{token}/conditions")
@Tag(name = "RESPOND", description = "승인자 링크 응답")
public class PublicApproverConditionController {

  private final ApproverConditionService conditionService;

  public PublicApproverConditionController(ApproverConditionService conditionService) {
    this.conditionService = conditionService;
  }

  @Operation(
      summary = "승인자 링크 활성 조건 조회",
      description = "공동 승인자 링크는 같은 매물 대표 승인자의 활성 조건을 읽기 전용으로 받는다. 응답은 캐시하지 않는다.")
  @GetMapping
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<ApproverConditionResponse> get(
      @PathVariable String token, HttpServletRequest request) {
    ApproverConditionResponse body =
        conditionService.get(token, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  @Operation(
      summary = "승인자 조건 등록·변경",
      description = "새 버전을 만들고 이전 활성 버전은 대체한다. 기존 확정 방문에 영향이 있으면 확인 후에만 반영한다.")
  @PutMapping
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<ApproverConditionResponse> put(
      @PathVariable String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body,
      HttpServletRequest request) {
    ApproverConditionResponse response =
        conditionService.put(
            token, body, idempotencyKey, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }

  @Operation(summary = "승인자 조건 변경 영향 미리보기", description = "조건을 저장하지 않고 영향받는 확정 방문만 알린다.")
  @PostMapping("/impact-preview")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<ApproverConditionImpactPreviewResponse> preview(
      @PathVariable String token, @RequestBody JsonNode body, HttpServletRequest request) {
    ApproverConditionImpactPreviewResponse response =
        conditionService.preview(token, body, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }
}
