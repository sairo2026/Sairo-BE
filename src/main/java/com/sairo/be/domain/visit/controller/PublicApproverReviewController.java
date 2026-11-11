package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverPreferredBrokerListResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverPreferredBrokerResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverVisitReviewListResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverVisitReviewResponse;
import com.sairo.be.domain.visit.service.ApproverReviewService;
import com.sairo.be.domain.visit.service.ApproverReviewService.Upserted;
import com.sairo.be.domain.visit.service.PreferredBrokerService;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@Profile("!migrate")
@RestController
@RequestMapping("/api/public/approver-property-links/{token}")
@RequiresAccess(AccessPermission.GUEST_LINK)
@Tag(name = "RESPOND", description = "승인자 링크 응답")
public class PublicApproverReviewController {

  private final ApproverReviewService reviewService;
  private final PreferredBrokerService preferredService;

  public PublicApproverReviewController(
      ApproverReviewService reviewService, PreferredBrokerService preferredService) {
    this.reviewService = reviewService;
    this.preferredService = preferredService;
  }

  @Operation(
      summary = "승인자 방문 평가 대상 목록",
      description = "평가 기한이 남은 완료 방문과 최근 90일 안에 평가한 방문을 돌려준다. 고객 이름과 연락처는 없다.")
  @GetMapping("/visit-reviews")
  public ResponseEntity<ApproverVisitReviewListResponse> reviews(
      @PathVariable String token, HttpServletRequest request) {
    return noStore(HttpStatus.OK, reviewService.list(token, ip(request)));
  }

  @Operation(
      summary = "방문 평가 입력·수정",
      description = "완료 방문 한 건의 세 항목을 예·아니오·답하지 않음으로 받는다. 점수와 별점은 없다. 수정은 기한 안에서 2회까지다.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "처음 입력한 평가를 저장했다."),
    @ApiResponse(responseCode = "200", description = "평가를 수정하거나 다시 입력했다.")
  })
  @PutMapping("/visit-reviews/{scheduleItemId}")
  public ResponseEntity<ApproverVisitReviewResponse> upsert(
      @PathVariable String token,
      @PathVariable Long scheduleItemId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body,
      HttpServletRequest request) {
    Upserted result =
        reviewService.upsert(token, scheduleItemId, ifMatch, idempotencyKey, body, ip(request));
    return noStore(result.created() ? HttpStatus.CREATED : HttpStatus.OK, result.body());
  }

  @Operation(
      summary = "방문 평가 철회",
      description = "본문 없이 If-Match로 현재 version을 보낸다. 철회한 평가는 기한 안에서 다시 입력한다.")
  @PostMapping("/visit-reviews/{scheduleItemId}/withdraw")
  public ResponseEntity<ApproverVisitReviewResponse> withdraw(
      @PathVariable String token,
      @PathVariable Long scheduleItemId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      HttpServletRequest request) {
    return noStore(
        HttpStatus.OK, reviewService.withdraw(token, scheduleItemId, ifMatch, ip(request)));
  }

  @Operation(
      summary = "선호 중개사 목록과 지정 후보",
      description = "선호 지정은 표시 순서와 칩에만 쓰이며 선택·확정·재검증에 영향을 주지 않는다.")
  @GetMapping("/preferred-brokers")
  public ResponseEntity<ApproverPreferredBrokerListResponse> preferredBrokers(
      @PathVariable String token, HttpServletRequest request) {
    return noStore(HttpStatus.OK, preferredService.list(token, ip(request)));
  }

  @Operation(summary = "선호 중개사 지정", description = "신청 번호와 방문 번호 가운데 하나를 근거로 보낸다. 활성 지정은 5명까지다.")
  @PostMapping("/preferred-brokers")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<ApproverPreferredBrokerResponse> designate(
      @PathVariable String token, @RequestBody JsonNode body, HttpServletRequest request) {
    return noStore(HttpStatus.CREATED, preferredService.designate(token, body, ip(request)));
  }

  @Operation(summary = "선호 중개사 해제", description = "행을 지우지 않고 해제 시각을 남긴다.")
  @PostMapping("/preferred-brokers/{preferredBrokerId}/release")
  public ResponseEntity<ApproverPreferredBrokerResponse> release(
      @PathVariable String token,
      @PathVariable Long preferredBrokerId,
      HttpServletRequest request) {
    return noStore(HttpStatus.OK, preferredService.release(token, preferredBrokerId, ip(request)));
  }

  private static String ip(HttpServletRequest request) {
    return ClientRequestInfo.from(request).ipAddress();
  }

  private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
  }
}
