package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsContentRequests.AnnouncementPublishRequest;
import com.sairo.be.domain.ops.dto.request.OpsContentRequests.AnnouncementRequest;
import com.sairo.be.domain.ops.dto.request.OpsContentRequests.CompanyServiceInfoRequest;
import com.sairo.be.domain.ops.dto.request.OpsContentRequests.InquiryTestRequest;
import com.sairo.be.domain.ops.dto.request.OpsContentRequests.PolicyDocumentCreateRequest;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementEndResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementListResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementPreviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementRevisionResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementStateResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementSubmitReviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.CompanyServiceInfoCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.CompanyServiceInfoResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.InquiryTestResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.OpsPolicyDocumentListResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.PolicyDocumentCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.PolicyDocumentPublishResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.PolicyDocumentSubmitReviewResponse;
import com.sairo.be.domain.ops.service.OpsContentService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops")
@RequiresAccess(AccessPermission.OPERATOR)
@Tag(name = "OPS", description = "공지·정책 문서·회사 제공 정보")
public class OpsContentController {

  private final OpsContentService service;

  public OpsContentController(OpsContentService service) {
    this.service = service;
  }

  @Operation(summary = "공지 목록", description = "본문·대상·마케팅 여부·이전 버전을 작성 최근 순으로 보인다. status로 거른다.")
  @GetMapping("/announcements")
  @RequiresOperatorRole(OperatorRole.OPS)
  public AnnouncementListResponse announcements(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.announcements(status, PageQuery.of(page, size));
  }

  @Operation(summary = "공지 작성", description = "초안으로 만들고 작성자는 세션의 운영자다.")
  @PostMapping("/announcements")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.OPS)
  public AnnouncementStateResponse createAnnouncement(
      @Valid @RequestBody AnnouncementRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.createAnnouncement(principal.operatorId(), request);
  }

  @Operation(summary = "공지 검토 단계 전환", description = "초안을 검토 단계로 올리고 올린 운영자를 검토자로 기록한다.")
  @PostMapping("/announcements/{announcementId}/submit-review")
  @RequiresOperatorRole(OperatorRole.OPS)
  public AnnouncementSubmitReviewResponse submitAnnouncementReview(
      @PathVariable Long announcementId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.submitAnnouncementReview(principal.operatorId(), announcementId);
  }

  @Operation(
      summary = "공지 게시 전 미리보기",
      description = "대상 사무소 수, 마케팅 수신 미동의로 빠지는 수, 모바일 미리보기 문구를 보인다.")
  @GetMapping("/announcements/{announcementId}/preview")
  @RequiresOperatorRole(OperatorRole.OPS)
  public AnnouncementPreviewResponse previewAnnouncement(@PathVariable Long announcementId) {
    return service.previewAnnouncement(announcementId);
  }

  @Operation(summary = "공지 게시·예약", description = "검토 단계에서만 한다. 예약 시각이 없으면 즉시 게시, 있으면 예약이다.")
  @PostMapping("/announcements/{announcementId}/publish")
  @RequiresOperatorRole(OperatorRole.OPS)
  public AnnouncementStateResponse publishAnnouncement(
      @PathVariable Long announcementId,
      @Valid @RequestBody(required = false) AnnouncementPublishRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.publishAnnouncement(principal.operatorId(), announcementId, request);
  }

  @Operation(summary = "공지 종료", description = "게시 중인 공지를 종료하고 예약 공지는 예약 취소로 종료한다.")
  @PostMapping("/announcements/{announcementId}/end")
  @RequiresOperatorRole(OperatorRole.OPS)
  public AnnouncementEndResponse endAnnouncement(
      @PathVariable Long announcementId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.endAnnouncement(principal.operatorId(), announcementId);
  }

  @Operation(
      summary = "게시본 수정",
      description = "게시된 공지를 새 버전 초안으로 만들고 이전 게시본과 연결한다. 이전 게시본은 그대로 둔다.")
  @PostMapping("/announcements/{announcementId}/revisions")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.OPS)
  public AnnouncementRevisionResponse reviseAnnouncement(
      @PathVariable Long announcementId,
      @Valid @RequestBody AnnouncementRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.reviseAnnouncement(principal.operatorId(), announcementId, request);
  }

  @Operation(summary = "정책 문서 목록", description = "본문·버전·검토자·게시자를 보인다. docType·status로 거른다.")
  @GetMapping("/policy-documents")
  @RequiresOperatorRole(OperatorRole.OPS)
  public OpsPolicyDocumentListResponse policies(
      @RequestParam(required = false) String docType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.policies(docType, status, PageQuery.of(page, size));
  }

  @Operation(summary = "정책 문서 새 버전 등록", description = "초안으로 등록한다. 같은 종류·버전이 있으면 409다.")
  @PostMapping("/policy-documents")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.OPS)
  public PolicyDocumentCreateResponse createPolicy(
      @Valid @RequestBody PolicyDocumentCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.createPolicy(principal.operatorId(), request);
  }

  @Operation(summary = "정책 문서 검토 단계 전환", description = "초안을 검토 단계로 올리고 올린 운영자를 검토자로 기록한다.")
  @PostMapping("/policy-documents/{documentId}/submit-review")
  @RequiresOperatorRole(OperatorRole.OPS)
  public PolicyDocumentSubmitReviewResponse submitPolicyReview(
      @PathVariable Long documentId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.submitPolicyReview(principal.operatorId(), documentId);
  }

  @Operation(summary = "정책 문서 게시", description = "검토 단계에서만 게시한다. 요금 정책은 시행 30일 전까지만 게시할 수 있다.")
  @PostMapping("/policy-documents/{documentId}/publish")
  @RequiresOperatorRole(OperatorRole.OPS)
  public PolicyDocumentPublishResponse publishPolicy(
      @PathVariable Long documentId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.publishPolicy(principal.operatorId(), documentId);
  }

  @Operation(
      summary = "회사 제공 정보 버전과 운영 출시 준비 여부",
      description = "버전 최근 순 목록과 launchReady·missingItems를 보인다.")
  @GetMapping("/company-service-info")
  @RequiresOperatorRole(OperatorRole.OPS)
  public CompanyServiceInfoResponse companyInfos(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    return service.companyInfos(PageQuery.of(page, size));
  }

  @Operation(summary = "회사 제공 정보 입력", description = "새 버전으로 등록한다. 게시는 실제 문의 수신 시험 성공을 기록할 때 한다.")
  @PostMapping("/company-service-info")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.OPS)
  public CompanyServiceInfoCreateResponse createCompanyInfo(
      @Valid @RequestBody CompanyServiceInfoRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.createCompanyInfo(principal.operatorId(), request);
  }

  @Operation(
      summary = "문의 수신 시험 결과 기록",
      description = "성공이면 시험 시각과 검토자를 기록하고 게시 전 버전을 게시한다. 실패는 기록을 바꾸지 않는다.")
  @PostMapping("/company-service-info/{infoId}/inquiry-test")
  @RequiresOperatorRole(OperatorRole.OPS)
  public InquiryTestResponse recordInquiryTest(
      @PathVariable Long infoId,
      @Valid @RequestBody InquiryTestRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.recordInquiryTest(principal.operatorId(), infoId, request);
  }
}
