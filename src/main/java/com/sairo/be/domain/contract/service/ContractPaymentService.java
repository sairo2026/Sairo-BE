package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.dto.response.ContractPaymentScheduleResponse;
import com.sairo.be.domain.contract.dto.response.ContractPaymentScheduleResponse.PaymentItem;
import com.sairo.be.domain.contract.dto.response.ContractPaymentScheduleResponse.PaymentTransactionItem;
import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.entity.DealType;
import com.sairo.be.domain.contract.repository.ContractPaymentRepository;
import com.sairo.be.domain.contract.repository.ContractPaymentRepository.PaymentRow;
import com.sairo.be.domain.contract.repository.ContractPaymentRepository.ScheduleRow;
import com.sairo.be.domain.contract.repository.ContractRepository;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 계약금·중도금·잔금 예정과 실지급이다(CONTRACT-07). 중개보수와 따로 관리한다. 예정 합계가 매매가(매매)나 보증금(전세·월세)과 다르면 막지 않고 경고를 돌려주며
 * 불일치 사유는 계약에 저장한다. 합계가 맞으면 사유를 비운다.
 */
@Profile("!migrate")
@Service
public class ContractPaymentService {

  static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
  private static final int TEXT_MAX = 2_000;
  private static final Set<String> SCHEDULE_KEYS =
      Set.of(
          "installmentType",
          "sequenceNo",
          "scheduledDate",
          "scheduledAmount",
          "amountMismatchReason");
  private static final Set<String> PAYMENT_KEYS =
      Set.of("actualAmount", "actualDate", "supersedesTransactionId", "correctionReason");

  enum InstallmentType {
    DEPOSIT,
    INTERIM,
    BALANCE
  }

  private final ContractRepository contracts;
  private final ContractPaymentRepository payments;
  private final ContractSupport support;

  public ContractPaymentService(
      ContractRepository contracts, ContractPaymentRepository payments, ContractSupport support) {
    this.contracts = contracts;
    this.payments = payments;
    this.support = support;
  }

  @Transactional
  public ContractPaymentScheduleResponse createSchedule(
      StaffPrincipal principal, Long contractId, JsonNode body) {
    Instant now = support.now();
    ContractRow row = support.lock(principal.officeId(), contractId);
    CustomerInput.requireObject(body, SCHEDULE_KEYS);
    InstallmentType type =
        CustomerInput.requiredEnum(body, "installmentType", InstallmentType.class);
    long sequenceNo = CustomerInput.requiredLong(body, "sequenceNo");
    if (sequenceNo < 1 || sequenceNo > 100) {
      throw CustomerInput.invalid("sequenceNo", FieldErrorCode.OUT_OF_RANGE, "1부터 100까지입니다.");
    }
    LocalDate scheduledDate = CustomerInput.requiredDate(body, "scheduledDate");
    long amount = ContractFeeService.positive(body, "scheduledAmount");
    String reason = CustomerInput.optionalText(body, "amountMismatchReason", TEXT_MAX, true);
    ContractSupport.requireSignedOnce(row);
    if (payments.scheduleExists(contractId, type.name(), (int) sequenceNo)) {
      throw new BusinessException(ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
    }
    Long scheduleId =
        payments.insertSchedule(
            contractId, type.name(), (int) sequenceNo, scheduledDate, amount, now);
    long total =
        payments.schedules(contractId).stream().mapToLong(ScheduleRow::scheduledAmount).sum();
    Long base = contractAmount(row);
    boolean mismatch = base == null || base != total;
    if (!mismatch) {
      contracts.updatePaymentMismatchReason(contractId, null);
    } else if (reason != null) {
      contracts.updatePaymentMismatchReason(contractId, reason);
    }
    contracts.bumpVersion(contractId, now);
    ContractRow updated = reload(principal, contractId);
    return new ContractPaymentScheduleResponse(
        scheduleId,
        contractId,
        type.name(),
        amount,
        mismatch ? AMOUNT_MISMATCH : null,
        updated.officeId(),
        updated.version());
  }

  /** 실지급 기록이다. 확인자와 확인 시각은 요청자와 현재 시각이다. 정정은 같은 예정의 대체되지 않은 거래를 대체한다. */
  @Transactional
  public ContractPaymentScheduleResponse.PaymentResult pay(
      StaffPrincipal principal, Long scheduleId, JsonNode body) {
    Instant now = support.now();
    Long contractId =
        payments
            .lockScheduleContract(principal.officeId(), scheduleId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    CustomerInput.requireObject(body, PAYMENT_KEYS);
    long amount = ContractFeeService.positive(body, "actualAmount");
    LocalDate actualDate = CustomerInput.requiredDate(body, "actualDate");
    if (actualDate.isAfter(ContractSupport.today(now))) {
      throw CustomerInput.invalid("actualDate", FieldErrorCode.OUT_OF_RANGE, "미래 날짜는 입력할 수 없습니다.");
    }
    Long supersedes = CustomerInput.optionalLong(body, "supersedesTransactionId");
    String reason = CustomerInput.optionalText(body, "correctionReason", TEXT_MAX, true);
    if (supersedes != null) {
      if (reason == null) {
        throw CustomerInput.invalid("correctionReason", FieldErrorCode.REQUIRED, "정정 사유가 필요합니다.");
      }
      PaymentRow target =
          payments.payments(contractId).stream()
              .filter(p -> p.id().equals(supersedes) && p.scheduleId().equals(scheduleId))
              .findFirst()
              .orElseThrow(
                  () ->
                      CustomerInput.invalid(
                          "supersedesTransactionId",
                          FieldErrorCode.INVALID_VALUE,
                          "같은 예정의 거래여야 합니다."));
      if (target.superseded()) {
        throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      }
    } else if (reason != null) {
      throw CustomerInput.invalid(
          "correctionReason", FieldErrorCode.NOT_ALLOWED, "정정 거래에만 사유를 받습니다.");
    }
    Long id =
        payments.insertPayment(
            scheduleId, amount, actualDate, supersedes, principal.membershipId(), reason, now);
    contracts.bumpVersion(contractId, now);
    ContractRow row = reload(principal, contractId);
    return new ContractPaymentScheduleResponse.PaymentResult(
        id,
        scheduleId,
        amount,
        principal.membershipId(),
        TimeRules.toDisplayOffset(now),
        row.officeId(),
        row.version());
  }

  @Transactional(readOnly = true)
  public ContractPaymentScheduleResponse.PaymentList list(
      StaffPrincipal principal, Long contractId) {
    ContractRow row = reload(principal, contractId);
    List<PaymentRow> all = payments.payments(contractId);
    List<PaymentItem> items =
        payments.schedules(contractId).stream()
            .map(
                s -> {
                  List<PaymentRow> own =
                      all.stream().filter(p -> p.scheduleId().equals(s.id())).toList();
                  long paid =
                      own.stream()
                          .filter(p -> !p.superseded())
                          .mapToLong(PaymentRow::actualAmount)
                          .sum();
                  String status =
                      paid == 0 ? "UNPAID" : paid < s.scheduledAmount() ? "PARTIAL" : "PAID";
                  return new PaymentItem(
                      s.id(),
                      s.installmentType(),
                      s.sequenceNo(),
                      s.scheduledDate(),
                      s.scheduledAmount(),
                      paid,
                      status,
                      own.stream()
                          .map(
                              p ->
                                  new PaymentTransactionItem(
                                      p.id(),
                                      p.actualAmount(),
                                      p.actualDate(),
                                      p.confirmedByMembershipId(),
                                      p.confirmedByName(),
                                      TimeRules.toDisplayOffset(p.confirmedAt()),
                                      p.supersedesTransactionId(),
                                      p.correctionReason(),
                                      p.superseded()))
                          .toList(),
                      row.officeId(),
                      row.version());
                })
            .toList();
    long total = items.stream().mapToLong(PaymentItem::scheduledAmount).sum();
    Long base = contractAmount(row);
    boolean mismatch = !items.isEmpty() && !Objects.equals(base, total);
    return new ContractPaymentScheduleResponse.PaymentList(
        items,
        total,
        base,
        mismatch ? AMOUNT_MISMATCH : null,
        mismatch ? contracts.paymentMismatchReason(contractId).orElse(null) : null);
  }

  /** 대조 기준 금액이다. 매매는 매매가, 전세·월세는 보증금이다. */
  private static Long contractAmount(ContractRow row) {
    return row.dealType() == DealType.SALE
        ? row.values().salePrice()
        : row.values().depositAmount();
  }

  private ContractRow reload(StaffPrincipal principal, Long contractId) {
    return contracts
        .find(principal.officeId(), contractId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }
}
