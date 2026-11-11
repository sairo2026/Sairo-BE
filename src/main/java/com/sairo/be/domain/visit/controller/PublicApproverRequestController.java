package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.ApproverSelectionResponse;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse;
import com.sairo.be.domain.visit.service.ApproverRequestCompareService;
import com.sairo.be.domain.visit.service.ApproverSelectionService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/public/approver-property-links/{token}/visit-requests")
@Tag(name = "RESPOND", description = "승인자 링크 응답")
public class PublicApproverRequestController {

  private final ApproverRequestCompareService compareService;
  private final ApproverSelectionService selectionService;

  public PublicApproverRequestController(
      ApproverRequestCompareService compareService, ApproverSelectionService selectionService) {
    this.compareService = compareService;
    this.selectionService = selectionService;
  }

  @Operation(
      summary = "승인자 신청 비교 목록",
      description = "신청 대기 신청만 돌려준다. 고객 이름과 연락처는 없고 신뢰 요약과 선호 표시는 참고 정보이며 정렬·선택 기준이 아니다.")
  @GetMapping
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<ApproverVisitRequestCompareResponse> list(
      @PathVariable String token, HttpServletRequest request) {
    ApproverVisitRequestCompareResponse body =
        compareService.list(token, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  @Operation(
      summary = "승인자 신청 선택",
      description = "선택을 기록한 뒤 최신 조건·권한·일정 충돌을 재검증해 통과하면 확정한다. 재검증 실패도 200이다.")
  @PostMapping("/{requestId}/select")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<ApproverSelectionResponse> select(
      @PathVariable String token,
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body,
      HttpServletRequest request) {
    ApproverSelectionResponse response =
        selectionService.select(
            token,
            requestId,
            ifMatch,
            idempotencyKey,
            body,
            ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }
}
