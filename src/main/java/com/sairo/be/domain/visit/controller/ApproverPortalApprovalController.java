package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.ApproverConditionImpactPreviewResponse;
import com.sairo.be.domain.visit.dto.response.ApproverConditionResponse;
import com.sairo.be.domain.visit.dto.response.ApproverSelectionResponse;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse;
import com.sairo.be.domain.visit.dto.response.ConfirmedScheduleResponses.ApproverConfirmedScheduleResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverPreferredBrokerListResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverPreferredBrokerResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverVisitReviewListResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverVisitReviewResponse;
import com.sairo.be.domain.visit.service.ApproverConditionService;
import com.sairo.be.domain.visit.service.ApproverRequestCompareService;
import com.sairo.be.domain.visit.service.ApproverReviewService;
import com.sairo.be.domain.visit.service.ApproverReviewService.Upserted;
import com.sairo.be.domain.visit.service.ApproverSelectionService;
import com.sairo.be.domain.visit.service.PreferredBrokerService;
import com.sairo.be.domain.visit.service.PublicConfirmedScheduleService;
import com.sairo.be.global.security.ApproverPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 승인자 세션으로 연결된 출입승인의 조건·신청 비교·선택·확정 일정·평가·선호 중개사를 다룬다. 응답은 링크 경로와 같다. */
@Profile("!migrate")
@RestController
@RequestMapping("/api/approver-portal/approvals/{approvalId}")
@RequiresAccess(AccessPermission.APPROVER_SESSION)
@Tag(name = "RESPOND", description = "승인자 계정")
public class ApproverPortalApprovalController {

  private final ApproverConditionService conditionService;
  private final ApproverRequestCompareService compareService;
  private final ApproverSelectionService selectionService;
  private final PublicConfirmedScheduleService confirmedService;
  private final ApproverReviewService reviewService;
  private final PreferredBrokerService preferredService;

  public ApproverPortalApprovalController(
      ApproverConditionService conditionService,
      ApproverRequestCompareService compareService,
      ApproverSelectionService selectionService,
      PublicConfirmedScheduleService confirmedService,
      ApproverReviewService reviewService,
      PreferredBrokerService preferredService) {
    this.conditionService = conditionService;
    this.compareService = compareService;
    this.selectionService = selectionService;
    this.confirmedService = confirmedService;
    this.reviewService = reviewService;
    this.preferredService = preferredService;
  }

  @Operation(summary = "승인자 세션 활성 조건 조회", description = "공동 승인자는 대표 승인자 출입승인의 조건을 읽는다.")
  @GetMapping("/conditions")
  public ResponseEntity<ApproverConditionResponse> conditions(
      @PathVariable Long approvalId, @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(
        HttpStatus.OK, conditionService.getForProfile(principal.profileId(), approvalId));
  }

  @Operation(summary = "승인자 세션 조건 등록·변경", description = "대표 승인자 출입승인만 가능하며 영향 확인 규칙은 링크 경로와 같다.")
  @PutMapping("/conditions")
  public ResponseEntity<ApproverConditionResponse> putConditions(
      @PathVariable Long approvalId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(
        HttpStatus.OK,
        conditionService.putForProfile(principal.profileId(), approvalId, body, idempotencyKey));
  }

  @Operation(summary = "승인자 세션 조건 변경 영향 미리보기", description = "조건을 저장하지 않고 영향받는 확정 방문만 알린다.")
  @PostMapping("/conditions/impact-preview")
  public ResponseEntity<ApproverConditionImpactPreviewResponse> previewConditions(
      @PathVariable Long approvalId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(
        HttpStatus.OK, conditionService.previewForProfile(principal.profileId(), approvalId, body));
  }

  @Operation(summary = "승인자 세션 신청 비교 목록", description = "대표 승인자 출입승인만 볼 수 있다. 고객 이름과 연락처는 없다.")
  @GetMapping("/visit-requests")
  public ResponseEntity<ApproverVisitRequestCompareResponse> visitRequests(
      @PathVariable Long approvalId, @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(HttpStatus.OK, compareService.listForProfile(principal.profileId(), approvalId));
  }

  @Operation(
      summary = "승인자 세션 신청 선택",
      description = "최신 조건·권한·일정 충돌을 재검증해 통과하면 확정한다. 재검증 실패도 200이다.")
  @PostMapping("/visit-requests/{requestId}/select")
  public ResponseEntity<ApproverSelectionResponse> select(
      @PathVariable Long approvalId,
      @PathVariable Long requestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(
        HttpStatus.OK,
        selectionService.selectForProfile(
            principal.profileId(), approvalId, requestId, ifMatch, idempotencyKey, body));
  }

  @Operation(summary = "승인자 세션 확정 일정", description = "본인 출입승인 매물의 확정 일정을 읽기 전용으로 본다. 공동 승인자도 본다.")
  @GetMapping("/confirmed-schedules")
  public ResponseEntity<ApproverConfirmedScheduleResponse> confirmedSchedules(
      @PathVariable Long approvalId, @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(
        HttpStatus.OK, confirmedService.approverForProfile(principal.profileId(), approvalId));
  }

  @Operation(summary = "승인자 세션 방문 평가 대상 목록", description = "응답은 링크 경로와 같다.")
  @GetMapping("/visit-reviews")
  public ResponseEntity<ApproverVisitReviewListResponse> reviews(
      @PathVariable Long approvalId, @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(HttpStatus.OK, reviewService.listForProfile(principal.profileId(), approvalId));
  }

  @Operation(summary = "승인자 세션 방문 평가 입력·수정", description = "대표 승인자 출입승인만 가능하다. 점수와 별점은 없다.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "처음 입력한 평가를 저장했다."),
    @ApiResponse(responseCode = "200", description = "평가를 수정하거나 다시 입력했다.")
  })
  @PutMapping("/visit-reviews/{scheduleItemId}")
  public ResponseEntity<ApproverVisitReviewResponse> upsertReview(
      @PathVariable Long approvalId,
      @PathVariable Long scheduleItemId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal ApproverPrincipal principal) {
    Upserted result =
        reviewService.upsertForProfile(
            principal.profileId(), approvalId, scheduleItemId, ifMatch, idempotencyKey, body);
    return noStore(result.created() ? HttpStatus.CREATED : HttpStatus.OK, result.body());
  }

  @Operation(summary = "승인자 세션 방문 평가 철회", description = "본문 없이 If-Match로 현재 version을 보낸다.")
  @PostMapping("/visit-reviews/{scheduleItemId}/withdraw")
  public ResponseEntity<ApproverVisitReviewResponse> withdrawReview(
      @PathVariable Long approvalId,
      @PathVariable Long scheduleItemId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(
        HttpStatus.OK,
        reviewService.withdrawForProfile(
            principal.profileId(), approvalId, scheduleItemId, ifMatch));
  }

  @Operation(summary = "승인자 세션 선호 중개사 목록", description = "응답은 링크 경로와 같다.")
  @GetMapping("/preferred-brokers")
  public ResponseEntity<ApproverPreferredBrokerListResponse> preferredBrokers(
      @PathVariable Long approvalId, @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(
        HttpStatus.OK, preferredService.listForProfile(principal.profileId(), approvalId));
  }

  @Operation(summary = "승인자 세션 선호 중개사 지정", description = "대표 승인자 출입승인만 가능하다.")
  @PostMapping("/preferred-brokers")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<ApproverPreferredBrokerResponse> designate(
      @PathVariable Long approvalId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(
        HttpStatus.CREATED,
        preferredService.designateForProfile(principal.profileId(), approvalId, body));
  }

  @Operation(summary = "승인자 세션 선호 중개사 해제", description = "행을 지우지 않고 해제 시각을 남긴다.")
  @PostMapping("/preferred-brokers/{preferredBrokerId}/release")
  public ResponseEntity<ApproverPreferredBrokerResponse> release(
      @PathVariable Long approvalId,
      @PathVariable Long preferredBrokerId,
      @AuthenticationPrincipal ApproverPrincipal principal) {
    return noStore(
        HttpStatus.OK,
        preferredService.releaseForProfile(principal.profileId(), approvalId, preferredBrokerId));
  }

  private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
  }
}
