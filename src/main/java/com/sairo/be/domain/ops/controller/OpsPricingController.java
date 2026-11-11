package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsPricingRequests.PriceChangeNoticeCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsPricingRequests.StartCustomerAgreementCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsPricingRequests.StartCustomerAgreementRevokeRequest;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.AgreementApproveResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.AgreementCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.AgreementListResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.AgreementRevokeResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.NoticeCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.NoticeListResponse;
import com.sairo.be.domain.ops.service.OpsPricingService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops")
@RequiresAccess(AccessPermission.OPERATOR)
@Tag(name = "OPS", description = "시작 고객 합의·가격 변경 안내")
public class OpsPricingController {

  private final OpsPricingService service;

  public OpsPricingController(OpsPricingService service) {
    this.service = service;
  }

  @Operation(
      summary = "시작 고객 합의 목록",
      description = "status·trialEventId·officeId로 거르고 이벤트별 승인 건수·상한·운영 목표를 함께 보인다.")
  @GetMapping("/start-customer-agreements")
  @RequiresOperatorRole({OperatorRole.OPS, OperatorRole.BILLING})
  public AgreementListResponse agreements(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long trialEventId,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.agreements(status, trialEventId, officeId, PageQuery.of(page, size));
  }

  @Operation(
      summary = "시작 고객 합의 등록",
      description = "체결일·합의서 참조번호·협력 조건 확인·가격 버전을 등록한다. 등록은 승인이 아니며 가격 보장 기간은 서버가 계산한다.")
  @PostMapping("/start-customer-agreements")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.OPS)
  public AgreementCreateResponse create(
      @Valid @RequestBody StartCustomerAgreementCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.createAgreement(principal.operatorId(), request);
  }

  @Operation(
      summary = "시작 고객 합의 승인",
      description = "등록자와 다른 결제담당만 승인한다. If-Match가 필요하고 이벤트 가입 창과 상한을 검사한 뒤 구독을 승격한다.")
  @PostMapping("/start-customer-agreements/{agreementId}/approve")
  @RequiresOperatorRole(OperatorRole.BILLING)
  public AgreementApproveResponse approve(
      @PathVariable Long agreementId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.approve(principal.operatorId(), agreementId, ifMatch);
  }

  @Operation(
      summary = "시작 고객 합의 취소",
      description = "사유가 필수이고 If-Match가 필요하다. 이미 부여한 무료 기간은 바꾸지 않는다.")
  @PostMapping("/start-customer-agreements/{agreementId}/revoke")
  @RequiresOperatorRole(OperatorRole.BILLING)
  public AgreementRevokeResponse revoke(
      @PathVariable Long agreementId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody StartCustomerAgreementRevokeRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.revoke(principal.operatorId(), agreementId, ifMatch, request);
  }

  @Operation(
      summary = "가격 변경 안내 현황",
      description = "priceScheduleId·decision·officeId로 거르고 필터 전체의 결정별 건수를 함께 보인다.")
  @GetMapping("/price-change-notices")
  @RequiresOperatorRole(OperatorRole.BILLING)
  public NoticeListResponse notices(
      @RequestParam(required = false) Long priceScheduleId,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.notices(priceScheduleId, decision, officeId, PageQuery.of(page, size));
  }

  @Operation(
      summary = "가격 변경 안내 생성",
      description = "예약 상태의 새 표준 요금표 버전으로 사무소별 안내를 만들고 대표 알림함에 남긴다. 효력 시각이 30일 뒤보다 이르면 422다.")
  @PostMapping("/price-schedules/{scheduleId}/notices")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.BILLING)
  public NoticeCreateResponse createNotices(
      @PathVariable Long scheduleId,
      @Valid @RequestBody(required = false) PriceChangeNoticeCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.createNotices(principal.operatorId(), scheduleId, request);
  }
}
