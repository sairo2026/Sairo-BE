package com.sairo.be.domain.migration.controller;

import com.sairo.be.domain.migration.dto.response.ImportSupportApproveResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportCancelResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportChangeResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportDetailResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportListItem;
import com.sairo.be.domain.migration.dto.response.ImportSupportPaymentConfirmResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportQuoteAcceptResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportStatusResponse;
import com.sairo.be.domain.migration.service.ImportSupportService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/import-support-requests")
@Tag(name = "IMPORT_SUPPORT", description = "유료 이전 지원 신청·견적 수락·결제·검수")
public class ImportSupportRequestController {

  private final ImportSupportService supportService;

  public ImportSupportRequestController(ImportSupportService supportService) {
    this.supportService = supportService;
  }

  @Operation(
      summary = "유료 이전 지원 신청",
      description =
          "업로드해 둔 이전 실행 단위(UPLOADED·MAPPING·PREVIEW)를 연결한다. 취소·거절 전까지 그 실행 단위는 직접 매핑·실행할 수 없다.")
  @PostMapping
  @RequiresAccess(AccessPermission.OWNER)
  public ImportSupportStatusResponse create(
      @RequestBody JsonNode body, @AuthenticationPrincipal StaffPrincipal principal) {
    return supportService.create(principal, body);
  }

  @Operation(summary = "내 유료 이전 지원 신청 목록", description = "status(선택), page, size(25·50·100)를 받는다.")
  @GetMapping
  @RequiresAccess(AccessPermission.OWNER)
  public PageResponse<ImportSupportListItem> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return supportService.list(principal, status, page, size);
  }

  @Operation(summary = "유료 이전 지원 진행·검수 대상 매핑 조회")
  @GetMapping("/{requestId}")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportSupportDetailResponse detail(
      @PathVariable Long requestId, @AuthenticationPrincipal StaffPrincipal principal) {
    return supportService.detail(principal, requestId);
  }

  @Operation(
      summary = "견적 수락",
      description = "If-Match를 쓰지 않는다. 이미 수락한 신청을 다시 호출하면 같은 결제 주문을 돌려준다.")
  @PostMapping("/{requestId}/accept-quote")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportSupportQuoteAcceptResponse acceptQuote(
      @PathVariable Long requestId, @AuthenticationPrincipal StaffPrincipal principal) {
    return supportService.acceptQuote(principal, requestId);
  }

  @Operation(summary = "일반결제 확정", description = "ACCEPTED에서 PAID로 바꾼다. 주문 ID와 금액을 견적 수락 때 값과 대조한다.")
  @PostMapping("/{requestId}/payment/confirm")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportSupportPaymentConfirmResponse confirmPayment(
      @PathVariable Long requestId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return supportService.confirmPayment(principal, requestId, body);
  }

  @Operation(summary = "검수 제출된 매핑 승인", description = "REVIEW에서만 가능하다. If-Match에 현재 version을 보낸다.")
  @PostMapping("/{requestId}/approve")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportSupportApproveResponse approve(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return supportService.approve(principal, requestId, IfMatchVersion.parse(ifMatch));
  }

  @Operation(summary = "검수 중 수정 요청", description = "표준 범위 1회. If-Match에 현재 version을 보낸다.")
  @PostMapping("/{requestId}/request-changes")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportSupportChangeResponse requestChanges(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return supportService.requestChanges(principal, requestId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "결제 전 신청 취소", description = "결제 후에는 취소할 수 없다. If-Match에 현재 version을 보낸다.")
  @PostMapping("/{requestId}/cancel")
  @RequiresAccess(AccessPermission.OWNER)
  public ImportSupportCancelResponse cancel(
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody(required = false) JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return supportService.cancel(principal, requestId, IfMatchVersion.parse(ifMatch), body);
  }
}
