package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OfficeRegistrationReviewChecklistRequest;
import com.sairo.be.domain.ops.dto.request.OperatorEvidenceDownloadRequest;
import com.sairo.be.domain.ops.dto.request.OpsOfficeRevokeRequest;
import com.sairo.be.domain.ops.dto.request.OpsRegistrationRejectRequest;
import com.sairo.be.domain.ops.dto.request.OpsRegistrationSupplementRequest;
import com.sairo.be.domain.ops.dto.response.EvidenceDownloadUrlResponse;
import com.sairo.be.domain.ops.dto.response.OfficeRegistrationReviewChecklistResponse;
import com.sairo.be.domain.ops.dto.response.OperatorRegistrationDetailResponse;
import com.sairo.be.domain.ops.dto.response.OperatorRegistrationListItemResponse;
import com.sairo.be.domain.ops.dto.response.OpsOfficeRevokeResponse;
import com.sairo.be.domain.ops.dto.response.OpsRegistrationResponse;
import com.sairo.be.domain.ops.service.OperatorRegistrationReviewService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops")
@RequiresAccess(AccessPermission.OPERATOR)
@RequiresOperatorRole(OperatorRole.REVIEW)
@Tag(name = "OPS", description = "사무소 인증 심사")
public class OperatorRegistrationReviewController {

  private final OperatorRegistrationReviewService reviewService;

  public OperatorRegistrationReviewController(OperatorRegistrationReviewService reviewService) {
    this.reviewService = reviewService;
  }

  @Operation(summary = "인증심사 대기 목록", description = "status를 생략하면 심사 중 신청만 오래된 순으로 돌려준다.")
  @GetMapping("/office-registrations")
  public PageResponse<OperatorRegistrationListItemResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return reviewService.list(status, PageQuery.of(page, size));
  }

  @Operation(summary = "인증 신청 상세 조회", description = "증빙 원문은 포함하지 않는다.")
  @GetMapping("/office-registrations/{registrationId}")
  public OperatorRegistrationDetailResponse detail(@PathVariable Long registrationId) {
    return reviewService.detail(registrationId);
  }

  @Operation(summary = "필수 확인 항목 저장", description = "현재 제출 버전 기준이며 보낸 항목만 바꾼다.")
  @PutMapping("/office-registrations/{registrationId}/checklist")
  public OfficeRegistrationReviewChecklistResponse updateChecklist(
      @PathVariable Long registrationId,
      @Valid @RequestBody OfficeRegistrationReviewChecklistRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return reviewService.updateChecklist(principal.operatorId(), registrationId, request);
  }

  @Operation(summary = "인증 신청 승인", description = "필수 확인 항목이 모두 끝난 신청만 승인하고 사무소와 대표 소속을 만든다.")
  @PostMapping("/office-registrations/{registrationId}/approve")
  public OpsRegistrationResponse approve(
      @PathVariable Long registrationId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return reviewService.approve(principal.operatorId(), registrationId);
  }

  @Operation(summary = "보완 요청", description = "고객에게 공개되는 사유와 보완 기한이 필요하다.")
  @PostMapping("/office-registrations/{registrationId}/request-supplement")
  public OpsRegistrationResponse requestSupplement(
      @PathVariable Long registrationId,
      @Valid @RequestBody OpsRegistrationSupplementRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return reviewService.requestSupplement(principal.operatorId(), registrationId, request);
  }

  @Operation(summary = "증빙서류 다운로드 URL 발급", description = "신청 상태가 심사 중이거나 보완 요청일 때만 단기 URL을 만든다.")
  @PostMapping("/office-registrations/{registrationId}/evidence/download-url")
  public EvidenceDownloadUrlResponse downloadUrl(
      @PathVariable Long registrationId,
      @Valid @RequestBody OperatorEvidenceDownloadRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return reviewService.issueDownloadUrl(
        principal.operatorId(), registrationId, request.evidenceType());
  }

  @Operation(summary = "인증 신청 반려", description = "고객에게 공개되는 사유가 필요하다.")
  @PostMapping("/office-registrations/{registrationId}/reject")
  public OpsRegistrationResponse reject(
      @PathVariable Long registrationId,
      @Valid @RequestBody OpsRegistrationRejectRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return reviewService.reject(principal.operatorId(), registrationId, request);
  }

  @Operation(summary = "인증 완료 사무소 회수", description = "회수 즉시 해당 사무소 구성원의 세션을 종료한다.")
  @PostMapping("/offices/{officeId}/revoke")
  public OpsOfficeRevokeResponse revoke(
      @PathVariable Long officeId,
      @Valid @RequestBody OpsOfficeRevokeRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return reviewService.revokeOffice(principal.operatorId(), officeId, request);
  }
}
