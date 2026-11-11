package com.sairo.be.domain.contract.controller;

import com.sairo.be.domain.contract.dto.response.ContractDocumentResponse;
import com.sairo.be.domain.contract.dto.response.ContractFeeResponse;
import com.sairo.be.domain.contract.dto.response.ContractFollowupTaskResponse;
import com.sairo.be.domain.contract.dto.response.ContractPaymentScheduleResponse;
import com.sairo.be.domain.contract.service.ContractDocumentService;
import com.sairo.be.domain.contract.service.ContractFeeService;
import com.sairo.be.domain.contract.service.ContractFollowupService;
import com.sairo.be.domain.contract.service.ContractPaymentService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/** 계약 서류(CONTRACT-04), 중개보수(CONTRACT-06), 계약대금(CONTRACT-07), 후속업무(CONTRACT-08)다. */
@Profile("!migrate")
@RestController
@RequiresAccess(AccessPermission.AGENT_OR_OWNER)
@Tag(name = "CONTRACT", description = "계약")
public class ContractRecordController {

  private final ContractDocumentService documentService;
  private final ContractFeeService feeService;
  private final ContractPaymentService paymentService;
  private final ContractFollowupService followupService;

  public ContractRecordController(
      ContractDocumentService documentService,
      ContractFeeService feeService,
      ContractPaymentService paymentService,
      ContractFollowupService followupService) {
    this.documentService = documentService;
    this.feeService = feeService;
    this.paymentService = paymentService;
    this.followupService = followupService;
  }

  @Operation(
      summary = "계약 서류 업로드",
      description =
          "멀티파트로 file과 contractRevisionId, documentType, writtenDate, deliveredDate,"
              + " datePendingReason, piiReviewed(true만), piiMaskingConfirmed를 보낸다. 체결 뒤에만 올린다.")
  @PostMapping(
      value = "/api/contracts/{contractId}/documents",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ContractDocumentResponse uploadDocument(
      @PathVariable Long contractId,
      @RequestParam Map<String, String> params,
      @RequestPart(value = "file", required = false) MultipartFile file,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return documentService.upload(principal, contractId, params, file);
  }

  @Operation(summary = "서류 정정본 업로드", description = "기존 서류는 그대로 두고 다음 버전으로 더한다. 서류 종류는 원래 서류를 따른다.")
  @PostMapping(
      value = "/api/contracts/{contractId}/documents/{documentId}/revision",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ContractDocumentResponse reviseDocument(
      @PathVariable Long contractId,
      @PathVariable Long documentId,
      @RequestParam Map<String, String> params,
      @RequestPart(value = "file", required = false) MultipartFile file,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return documentService.revise(principal, contractId, documentId, params, file);
  }

  @Operation(summary = "서류 다운로드 링크", description = "대표·소속공인중개사만 받는다. 링크는 짧게 유효하다.")
  @GetMapping("/api/contracts/{contractId}/documents/{documentId}")
  public ContractDocumentResponse.DocumentDownload downloadDocument(
      @PathVariable Long contractId,
      @PathVariable Long documentId,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return documentService.download(principal, contractId, documentId);
  }

  @Operation(summary = "중개보수 청구 등록", description = "체결 뒤에만 등록한다. 같은 트랜잭션에서 최초 청구 사건을 남긴다.")
  @PostMapping("/api/contracts/{contractId}/fees")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractFeeResponse createFee(
      @PathVariable Long contractId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return feeService.create(principal, contractId, body);
  }

  @Operation(summary = "중개보수 내역", description = "청구·조정·수납·환급 내역과 지급의무자별 미수금·초과수납이다.")
  @GetMapping("/api/contracts/{contractId}/fees")
  public ContractFeeResponse.FeeList fees(
      @PathVariable Long contractId, @AuthenticationPrincipal StaffPrincipal principal) {
    return feeService.list(principal, contractId);
  }

  @Operation(
      summary = "약정보수 조정·정정",
      description = "ADJUSTED는 이번 달 이후 효력일만 받는다. CORRECTED는 supersedesRevisionId로 같은 청구의 사건을 대체한다.")
  @PostMapping("/api/contract-fees/{feeId}/revisions")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractFeeResponse.FeeRevisionResult reviseFee(
      @PathVariable Long feeId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return feeService.revise(principal, feeId, body);
  }

  @Operation(
      summary = "중개보수 수납·환급 기록",
      description = "오입력은 supersedesTransactionId와 correctionReason으로 대체한다.")
  @PostMapping("/api/contract-fees/{feeId}/transactions")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractFeeResponse.FeeTransactionResult feeTransaction(
      @PathVariable Long feeId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return feeService.transact(principal, feeId, body);
  }

  @Operation(summary = "계약대금 예정 등록", description = "체결 뒤에만 등록한다. 합계가 매매가·보증금과 다르면 경고만 돌려준다.")
  @PostMapping("/api/contracts/{contractId}/payment-schedules")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractPaymentScheduleResponse createSchedule(
      @PathVariable Long contractId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return paymentService.createSchedule(principal, contractId, body);
  }

  @Operation(summary = "계약대금 예정과 실지급 내역", description = "예정 합계와 매매가·보증금 대조 결과를 함께 돌려준다.")
  @GetMapping("/api/contracts/{contractId}/payment-schedules")
  public ContractPaymentScheduleResponse.PaymentList schedules(
      @PathVariable Long contractId, @AuthenticationPrincipal StaffPrincipal principal) {
    return paymentService.list(principal, contractId);
  }

  @Operation(summary = "계약대금 실지급 기록", description = "확인자와 확인 시각은 요청자와 현재 시각이다. 정정은 대체 거래로 한다.")
  @PostMapping("/api/payment-schedules/{scheduleId}/transactions")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractPaymentScheduleResponse.PaymentResult pay(
      @PathVariable Long scheduleId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return paymentService.pay(principal, scheduleId, body);
  }

  @Operation(summary = "서류·신고 후속업무 목록", description = "잔금·만기 업무는 홈 업무 목록에 있다.")
  @GetMapping("/api/contracts/{contractId}/followup-tasks")
  public ContractFollowupTaskResponse.FollowupList followups(
      @PathVariable Long contractId, @AuthenticationPrincipal StaffPrincipal principal) {
    return followupService.list(principal, contractId);
  }

  @Operation(
      summary = "후속업무 제출 처리",
      description = "submitted=true만 받는다. If-Match에 계약 version을 보낸다. 자동 신고는 하지 않는다.")
  @PatchMapping("/api/contract-followup-tasks/{taskId}")
  public ContractFollowupTaskResponse submitFollowup(
      @PathVariable Long taskId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return followupService.submit(principal, taskId, IfMatchVersion.parse(ifMatch), body);
  }
}
