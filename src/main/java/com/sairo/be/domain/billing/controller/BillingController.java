package com.sairo.be.domain.billing.controller;

import com.sairo.be.domain.billing.dto.request.BillingKeyRegisterRequest;
import com.sairo.be.domain.billing.dto.request.RefundRequestCreateRequest;
import com.sairo.be.domain.billing.dto.request.SubscriptionPurchaseConsentRequest;
import com.sairo.be.domain.billing.dto.request.TaxRecipientUpsertRequest;
import com.sairo.be.domain.billing.dto.response.BillingInvoiceDetailResponse;
import com.sairo.be.domain.billing.dto.response.BillingInvoiceItemResponse;
import com.sairo.be.domain.billing.dto.response.BillingKeyRegisterResponse;
import com.sairo.be.domain.billing.dto.response.BillingKeySessionResponse;
import com.sairo.be.domain.billing.dto.response.BillingPlanListResponse;
import com.sairo.be.domain.billing.dto.response.PriceChangeNoticeDeclineResponse;
import com.sairo.be.domain.billing.dto.response.PriceChangeNoticeItemResponse;
import com.sairo.be.domain.billing.dto.response.PurchaseConsentPreviewResponse;
import com.sairo.be.domain.billing.dto.response.RefundRequestCreateResponse;
import com.sairo.be.domain.billing.dto.response.RefundRequestItemResponse;
import com.sairo.be.domain.billing.dto.response.RefundRequestStatusResponse;
import com.sairo.be.domain.billing.dto.response.StartCustomerAgreementResponse;
import com.sairo.be.domain.billing.dto.response.SubscriptionCurrentResponse;
import com.sairo.be.domain.billing.dto.response.SubscriptionPurchaseConsentResponse;
import com.sairo.be.domain.billing.dto.response.TaxRecipientResponse;
import com.sairo.be.domain.billing.service.BillingKeyService;
import com.sairo.be.domain.billing.service.BillingPlanService;
import com.sairo.be.domain.billing.service.InvoiceQueryService;
import com.sairo.be.domain.billing.service.PriceChangeNoticeService;
import com.sairo.be.domain.billing.service.PurchaseConsentService;
import com.sairo.be.domain.billing.service.RefundRequestService;
import com.sairo.be.domain.billing.service.RefundRequestService.CreateResult;
import com.sairo.be.domain.billing.service.StartCustomerAgreementService;
import com.sairo.be.domain.billing.service.SubscriptionCurrentService;
import com.sairo.be.domain.billing.service.TaxRecipientService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api")
@Tag(name = "BILLING", description = "요금·구독·결제")
public class BillingController {

  private final BillingPlanService planService;
  private final SubscriptionCurrentService currentService;
  private final BillingKeyService billingKeyService;
  private final PurchaseConsentService consentService;
  private final InvoiceQueryService invoiceService;
  private final RefundRequestService refundService;
  private final StartCustomerAgreementService agreementService;
  private final PriceChangeNoticeService noticeService;
  private final TaxRecipientService taxRecipientService;

  public BillingController(
      BillingPlanService planService,
      SubscriptionCurrentService currentService,
      BillingKeyService billingKeyService,
      PurchaseConsentService consentService,
      InvoiceQueryService invoiceService,
      RefundRequestService refundService,
      StartCustomerAgreementService agreementService,
      PriceChangeNoticeService noticeService,
      TaxRecipientService taxRecipientService) {
    this.planService = planService;
    this.currentService = currentService;
    this.billingKeyService = billingKeyService;
    this.consentService = consentService;
    this.invoiceService = invoiceService;
    this.refundService = refundService;
    this.agreementService = agreementService;
    this.noticeService = noticeService;
    this.taxRecipientService = taxRecipientService;
  }

  @Operation(summary = "요금제 목록", description = "현재 과금 인원 기준 예상 금액과 적용 가격 버전을 함께 돌려준다.")
  @GetMapping("/billing/plans")
  @RequiresAccess(AccessPermission.OWNER)
  public BillingPlanListResponse plans(@AuthenticationPrincipal StaffPrincipal principal) {
    return planService.plans(principal);
  }

  @Operation(
      summary = "구독 현황 조회",
      description = "무료체험 종료 시각, 결제 전 상태, 첫 청구 예정 시각, 등록 카드와 결제 준비 상태를 돌려준다.")
  @GetMapping("/subscriptions/current")
  @RequiresAccess(AccessPermission.OWNER)
  public SubscriptionCurrentResponse current(@AuthenticationPrincipal StaffPrincipal principal) {
    return currentService.current(principal);
  }

  @Operation(summary = "카드 등록 세션 발급", description = "카드 등록창을 열기 전 사무소별 고객 키를 발급한다. 재인증이 필요하다.")
  @PostMapping("/subscriptions/billing-key/session")
  @RequiresAccess(AccessPermission.OWNER)
  public BillingKeySessionResponse openBillingKeySession(
      @AuthenticationPrincipal StaffPrincipal principal, HttpSession session) {
    return billingKeyService.openSession(principal, session);
  }

  @Operation(summary = "카드 등록", description = "카드 등록창 성공 리다이렉트 값으로 빌링키를 발급받아 암호화 저장한다. 재인증이 필요하다.")
  @PostMapping("/subscriptions/billing-key")
  @RequiresAccess(AccessPermission.OWNER)
  public BillingKeyRegisterResponse registerBillingKey(
      @Valid @RequestBody BillingKeyRegisterRequest request,
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session) {
    return billingKeyService.register(principal, session, request);
  }

  @Operation(summary = "등록 카드 해제", description = "등록한 결제수단을 해제한다. 재인증이 필요하다.")
  @DeleteMapping("/subscriptions/billing-key")
  @RequiresAccess(AccessPermission.OWNER)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteBillingKey(
      @AuthenticationPrincipal StaffPrincipal principal, HttpSession session) {
    billingKeyService.delete(principal, session);
  }

  @Operation(
      summary = "구독 구매 동의 미리보기",
      description = "동의 화면에 보여줄 상품, 인원, 금액, 기간, 첫 청구 예정 시각과 약관 문구를 돌려준다.")
  @GetMapping("/subscriptions/purchase-consent/preview")
  @RequiresAccess(AccessPermission.OWNER)
  public PurchaseConsentPreviewResponse previewPurchaseConsent(
      @RequestParam(required = false) String plan,
      @RequestParam(required = false) Long priceChangeNoticeId,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return consentService.preview(principal, plan, priceChangeNoticeId);
  }

  @Operation(
      summary = "구독 구매 동의",
      description = "미리보기 값을 확인받아 동의 이벤트와 구독 구매 동의를 함께 저장한다. 동의는 청구가 아니다.")
  @PostMapping("/subscriptions/purchase-consent")
  @RequiresAccess(AccessPermission.OWNER)
  public SubscriptionPurchaseConsentResponse recordPurchaseConsent(
      @Valid @RequestBody SubscriptionPurchaseConsentRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return consentService.record(principal, request);
  }

  @Operation(summary = "청구서 목록", description = "자기 사무소의 청구서를 최신 청구 기간 순으로 돌려준다.")
  @GetMapping("/billing/invoices")
  @RequiresAccess(AccessPermission.OWNER)
  public PageResponse<BillingInvoiceItemResponse> invoices(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return invoiceService.list(principal, PageQuery.of(page, size));
  }

  @Operation(summary = "청구서 상세", description = "결제 시도 이력과 증빙 발급 상태를 포함한다.")
  @GetMapping("/billing/invoices/{invoiceId}")
  @RequiresAccess(AccessPermission.OWNER)
  public BillingInvoiceDetailResponse invoice(
      @PathVariable Long invoiceId, @AuthenticationPrincipal StaffPrincipal principal) {
    return invoiceService.detail(principal, invoiceId);
  }

  @Operation(summary = "환불 신청", description = "청구서와 사유 분류만 보낸다. 같은 청구서·사유의 재요청은 기존 신청을 돌려준다.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "새 환불 신청을 접수했다."),
    @ApiResponse(responseCode = "200", description = "같은 청구서·사유의 기존 신청을 돌려준다.")
  })
  @PostMapping("/refund-requests")
  @RequiresAccess(AccessPermission.OWNER)
  public ResponseEntity<RefundRequestCreateResponse> createRefundRequest(
      @Valid @RequestBody RefundRequestCreateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    CreateResult result = refundService.create(principal, request);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.body());
  }

  @Operation(summary = "환불 신청 목록", description = "청구서·상태로 걸러 최근 신청부터 돌려준다.")
  @GetMapping("/refund-requests")
  @RequiresAccess(AccessPermission.OWNER)
  public PageResponse<RefundRequestItemResponse> refundRequests(
      @RequestParam(required = false) Long invoiceId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return refundService.list(principal, invoiceId, status, PageQuery.of(page, size));
  }

  @Operation(summary = "환불 처리 상태 조회", description = "권리액 산정 근거를 포함한다.")
  @GetMapping("/refund-requests/{refundId}")
  @RequiresAccess(AccessPermission.OWNER)
  public RefundRequestStatusResponse refundRequest(
      @PathVariable Long refundId, @AuthenticationPrincipal StaffPrincipal principal) {
    return refundService.get(principal, refundId);
  }

  @Operation(summary = "시작 고객 합의 조회", description = "승인된 합의만 보인다. 등록만 됐거나 취소된 합의는 없는 것으로 본다.")
  @GetMapping("/subscriptions/start-customer-agreement")
  @RequiresAccess(AccessPermission.OWNER)
  public StartCustomerAgreementResponse startCustomerAgreement(
      @AuthenticationPrincipal StaffPrincipal principal) {
    return agreementService.get(principal);
  }

  @Operation(summary = "시작 고객 합의 확인", description = "대표의 확인 시각을 기록한다. 이미 확인했으면 최초 확인 시각을 바꾸지 않는다.")
  @PostMapping("/subscriptions/start-customer-agreement/acknowledge")
  @RequiresAccess(AccessPermission.OWNER)
  public StartCustomerAgreementResponse acknowledgeStartCustomerAgreement(
      @AuthenticationPrincipal StaffPrincipal principal) {
    return agreementService.acknowledge(principal);
  }

  @Operation(summary = "가격 변경 안내 목록", description = "자기 사무소의 안내를 최근 안내 시각 순으로 돌려준다.")
  @GetMapping("/subscriptions/price-change-notices")
  @RequiresAccess(AccessPermission.OWNER)
  public PageResponse<PriceChangeNoticeItemResponse> priceChangeNotices(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return noticeService.list(principal, PageQuery.of(page, size));
  }

  @Operation(summary = "가격 인상 안내 거절", description = "동의는 구독 구매 동의에 안내 ID를 담아 보낸다.")
  @PostMapping("/subscriptions/price-change-notices/{noticeId}/decline")
  @RequiresAccess(AccessPermission.OWNER)
  public PriceChangeNoticeDeclineResponse declinePriceChangeNotice(
      @PathVariable Long noticeId, @AuthenticationPrincipal StaffPrincipal principal) {
    return noticeService.decline(principal, noticeId);
  }

  @Operation(summary = "세금계산서 수신 정보 조회", description = "저장된 값이 없으면 사무소 정보 기반 초기값을 제안한다.")
  @GetMapping("/billing/tax-recipient")
  @RequiresAccess(AccessPermission.OWNER)
  public TaxRecipientResponse taxRecipient(@AuthenticationPrincipal StaffPrincipal principal) {
    return taxRecipientService.get(principal);
  }

  @Operation(summary = "세금계산서 수신 정보 저장", description = "이전 활성 행은 대체됨으로 바꾸고 새 행을 활성으로 만든다.")
  @PutMapping("/billing/tax-recipient")
  @RequiresAccess(AccessPermission.OWNER)
  public TaxRecipientResponse saveTaxRecipient(
      @Valid @RequestBody TaxRecipientUpsertRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return taxRecipientService.save(principal, request);
  }
}
