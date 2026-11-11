package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsBillingRequests.ReconciliationResolveRequest;
import com.sairo.be.domain.ops.dto.request.OpsBillingRequests.RefundReviewRequest;
import com.sairo.be.domain.ops.dto.request.OpsBillingRequests.TaxDocumentAmendRequest;
import com.sairo.be.domain.ops.dto.request.OpsBillingRequests.TaxDocumentRecordRequest;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.InvoiceDetail;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.InvoiceItem;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.ReconciliationItem;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.ReconciliationResolveResponse;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.RefundDetail;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.RefundExecuteResponse;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.RefundItem;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.RefundReviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.TaxDocumentItem;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.TaxDocumentResponse;
import com.sairo.be.domain.ops.service.OpsBillingService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
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
@RequiresOperatorRole(OperatorRole.BILLING)
@Tag(name = "OPS", description = "청구서·환불·PG 대사·증빙 발급")
public class OpsBillingController {

  private final OpsBillingService service;

  public OpsBillingController(OpsBillingService service) {
    this.service = service;
  }

  @Operation(
      summary = "전체 청구서 조회",
      description = "officeId·status·dueFrom·dueTo(납부기한 KST 날짜)로 거른다.")
  @GetMapping("/billing/invoices")
  public PageResponse<InvoiceItem> invoices(
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String dueFrom,
      @RequestParam(required = false) String dueTo,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.invoices(officeId, status, dueFrom, dueTo, PageQuery.of(page, size));
  }

  @Operation(summary = "청구서 상세", description = "결제 시도별 주문 ID와 연결 환불을 담는다. 결제키는 담지 않는다.")
  @GetMapping("/billing/invoices/{invoiceId}")
  public InvoiceDetail invoice(@PathVariable Long invoiceId) {
    return service.invoice(invoiceId);
  }

  @Operation(summary = "환불 신청 큐", description = "status·officeId로 거른다. 끝나지 않은 신청을 오래된 순으로 먼저 보인다.")
  @GetMapping("/refund-requests")
  public PageResponse<RefundItem> refundQueue(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.refundQueue(status, officeId, PageQuery.of(page, size));
  }

  @Operation(summary = "환불 사건 상세", description = "권리액 산정, 검토 이력, 앞 4자리만 남긴 PG 환불 참조를 담는다.")
  @GetMapping("/refund-requests/{refundId}")
  public RefundDetail refund(@PathVariable Long refundId) {
    return service.refund(refundId);
  }

  @Operation(
      summary = "환불 검토",
      description = "승인하면 권리액·예약액·환불 금액을 다시 산정해 고정하고 실행을 기다린다. 사건 ID가 없는 신청은 승인 때 채운다.")
  @PostMapping("/refund-requests/{refundId}/review")
  public RefundReviewResponse review(
      @PathVariable Long refundId,
      @Valid @RequestBody RefundReviewRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.review(principal.operatorId(), refundId, request);
  }

  @Operation(
      summary = "PG 취소 실행",
      description =
          "5분 안의 재인증이 필요하다. 결과가 불확실하면 확인 중으로 두고 다시 호출하면 같은 멱등키로 확인한다. 정책 외 보상은 승인한 운영자가 실행할 수 없다.")
  @PostMapping("/refund-requests/{refundId}/execute")
  public RefundExecuteResponse execute(
      @PathVariable Long refundId,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.execute(principal.operatorId(), refundId, session);
  }

  @Operation(
      summary = "PG 거래 대사 결과",
      description = "businessDate(기본 KST 전일)·status·assignedOperatorId·unresolved로 거른다.")
  @GetMapping("/billing/reconciliation")
  public PageResponse<ReconciliationItem> reconciliation(
      @RequestParam(required = false) String businessDate,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long assignedOperatorId,
      @RequestParam(required = false) Boolean unresolved,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.reconciliation(
        businessDate, status, assignedOperatorId, unresolved, PageQuery.of(page, size));
  }

  @Operation(summary = "대사 불일치 원인·해결 근거 기록", description = "일치 항목과 이미 해결한 항목은 409다.")
  @PostMapping("/billing/reconciliation/{entryId}/resolve")
  public ReconciliationResolveResponse resolve(
      @PathVariable Long entryId,
      @Valid @RequestBody ReconciliationResolveRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.resolve(principal.operatorId(), entryId, request);
  }

  @Operation(
      summary = "증빙 발급 상태 목록",
      description = "status·officeId·invoiceId·dueFrom·dueTo(발급 기한 힌트)로 거른다. 수정 발급은 원본 ID를 함께 보인다.")
  @GetMapping("/billing/tax-documents")
  public PageResponse<TaxDocumentItem> taxDocuments(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Long invoiceId,
      @RequestParam(required = false) String dueFrom,
      @RequestParam(required = false) String dueTo,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.taxDocuments(
        status, officeId, invoiceId, dueFrom, dueTo, PageQuery.of(page, size));
  }

  @Operation(
      summary = "증빙 발급 결과 기록",
      description = "국세청 서비스에서 발급한 결과를 기록한다. 결제 완료·환불 청구서만 받고 같은 종류의 발급됨이 있으면 409다.")
  @PostMapping("/billing/invoices/{invoiceId}/tax-documents")
  @ResponseStatus(HttpStatus.CREATED)
  public TaxDocumentResponse recordTaxDocument(
      @PathVariable Long invoiceId,
      @Valid @RequestBody TaxDocumentRecordRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.recordTaxDocument(principal.operatorId(), invoiceId, request);
  }

  @Operation(summary = "수정 발급 기록", description = "발급됨 원본을 고치지 않고 수정 발급 행을 새로 만든다.")
  @PostMapping("/billing/tax-documents/{documentId}/amend")
  @ResponseStatus(HttpStatus.CREATED)
  public TaxDocumentResponse amendTaxDocument(
      @PathVariable Long documentId,
      @Valid @RequestBody TaxDocumentAmendRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.amendTaxDocument(principal.operatorId(), documentId, request);
  }
}
