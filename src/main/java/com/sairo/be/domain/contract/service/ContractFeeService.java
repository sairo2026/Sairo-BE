package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.dto.response.ContractFeeResponse;
import com.sairo.be.domain.contract.dto.response.ContractFeeResponse.FeeItem;
import com.sairo.be.domain.contract.dto.response.ContractFeeResponse.FeePayer;
import com.sairo.be.domain.contract.dto.response.ContractFeeResponse.FeeRevisionItem;
import com.sairo.be.domain.contract.dto.response.ContractFeeResponse.FeeTransactionItem;
import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.entity.PartyRow;
import com.sairo.be.domain.contract.repository.ContractFeeRepository;
import com.sairo.be.domain.contract.repository.ContractFeeRepository.Amounts;
import com.sairo.be.domain.contract.repository.ContractFeeRepository.FeeRow;
import com.sairo.be.domain.contract.repository.ContractFeeRepository.RevisionRow;
import com.sairo.be.domain.contract.repository.ContractFeeRepository.TransactionRow;
import com.sairo.be.domain.contract.repository.ContractPartyRepository;
import com.sairo.be.domain.contract.repository.ContractRepository;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 중개보수 청구·약정보수 조정과 정정·수납과 환급이다(CONTRACT-06). 사건과 거래는 더하기만 하고, 과거 날짜에 영향을 주는 기록은 그 달부터 저장된 월말결산을 다시
 * 계산한다. 계약대금(CONTRACT-07)과 섞지 않는다.
 */
@Profile("!migrate")
@Service
public class ContractFeeService {

  static final int TEXT_MAX = 2_000;
  private static final Set<String> FEE_KEYS =
      Set.of(
          "payerType",
          "payerPartyId",
          "agreedFeeAmount",
          "vatIncluded",
          "supplyAmount",
          "taxAmount",
          "totalAmount",
          "billedAt",
          "dueAt");
  private static final Set<String> REVISION_KEYS =
      Set.of(
          "revisionType",
          "effectiveDate",
          "agreedFeeAmount",
          "supplyAmount",
          "taxAmount",
          "totalAmount",
          "reason",
          "supersedesRevisionId");
  private static final Set<String> TRANSACTION_KEYS =
      Set.of(
          "transactionType",
          "amount",
          "actualDate",
          "memo",
          "supersedesTransactionId",
          "correctionReason");

  enum PayerType {
    SELLER_LANDLORD,
    BUYER_TENANT
  }

  enum RevisionType {
    ADJUSTED,
    CORRECTED
  }

  enum TransactionType {
    RECEIPT,
    REFUND,
    ADJUSTMENT
  }

  private final ContractRepository contracts;
  private final ContractPartyRepository parties;
  private final ContractFeeRepository fees;
  private final ContractSupport support;

  public ContractFeeService(
      ContractRepository contracts,
      ContractPartyRepository parties,
      ContractFeeRepository fees,
      ContractSupport support) {
    this.contracts = contracts;
    this.parties = parties;
    this.fees = fees;
    this.support = support;
  }

  @Transactional
  public ContractFeeResponse create(StaffPrincipal principal, Long contractId, JsonNode body) {
    Instant now = support.now();
    ContractRow row = support.lock(principal.officeId(), contractId);
    CustomerInput.requireObject(body, FEE_KEYS);
    PayerType payerType = CustomerInput.requiredEnum(body, "payerType", PayerType.class);
    Long payerPartyId = CustomerInput.requiredLong(body, "payerPartyId");
    Boolean vatIncluded = CustomerInput.requiredBoolean(body, "vatIncluded");
    Amounts amounts = amounts(body);
    LocalDate billedAt =
        TimeRules.displayDate(CustomerInput.requiredDateTime(body, "billedAt").toInstant());
    LocalDate dueAt =
        CustomerInput.isNull(body, "dueAt")
            ? null
            : TimeRules.displayDate(CustomerInput.optionalDateTime(body, "dueAt").toInstant());
    ContractSupport.requireSignedOnce(row);
    boolean payerOk =
        parties.current(contractId).stream()
            .anyMatch(
                (PartyRow p) ->
                    p.id().equals(payerPartyId) && p.side().name().equals(payerType.name()));
    if (!payerOk) {
      throw new BusinessException(ErrorCode.INVALID_CONTRACT_PARTY);
    }
    Long feeId =
        fees.insertFee(
            contractId, payerType.name(), payerPartyId, vatIncluded, amounts, billedAt, dueAt, now);
    fees.insertRevision(
        feeId, "INITIAL", billedAt, amounts, null, principal.membershipId(), null, now);
    contracts.bumpVersion(contractId, now);
    recalculate(row, billedAt, now);
    ContractRow updated = reload(principal, contractId);
    return new ContractFeeResponse(
        feeId,
        contractId,
        payerPartyId,
        amounts.totalAmount(),
        updated.officeId(),
        updated.version());
  }

  /**
   * 약정보수 조정·정정이다. 정상 조정(ADJUSTED)은 이번 달 1일 이후 효력일만 받아 지난 달에 소급하지 않는다. 과거 오입력 정정(CORRECTED)은 같은 청구의
   * 대체되지 않은 사건을 대체하고 원래·새 효력일 가운데 이른 달부터 결산을 다시 계산한다.
   */
  @Transactional
  public ContractFeeResponse.FeeRevisionResult revise(
      StaffPrincipal principal, Long feeId, JsonNode body) {
    Instant now = support.now();
    FeeRow fee = lockFee(principal, feeId);
    CustomerInput.requireObject(body, REVISION_KEYS);
    RevisionType type = CustomerInput.requiredEnum(body, "revisionType", RevisionType.class);
    LocalDate effectiveDate = CustomerInput.requiredDate(body, "effectiveDate");
    Amounts amounts = amounts(body);
    String reason = CustomerInput.requiredText(body, "reason", TEXT_MAX, true);
    Long supersedes = CustomerInput.optionalLong(body, "supersedesRevisionId");
    List<RevisionRow> revisions =
        fees.revisions(fee.contractId()).stream().filter(r -> r.feeId().equals(feeId)).toList();
    LocalDate from = effectiveDate;
    if (type == RevisionType.ADJUSTED) {
      if (supersedes != null) {
        throw CustomerInput.invalid(
            "supersedesRevisionId", FieldErrorCode.NOT_ALLOWED, "정상 조정은 대체 대상을 받지 않습니다.");
      }
      if (effectiveDate.isBefore(ContractSupport.today(now).withDayOfMonth(1))) {
        throw CustomerInput.invalid(
            "effectiveDate", FieldErrorCode.OUT_OF_RANGE, "정상 조정은 지난 달로 소급할 수 없습니다.");
      }
    } else {
      if (supersedes == null) {
        throw CustomerInput.invalid(
            "supersedesRevisionId", FieldErrorCode.REQUIRED, "정정할 사건이 필요합니다.");
      }
      RevisionRow target =
          revisions.stream()
              .filter(r -> r.id().equals(supersedes))
              .findFirst()
              .orElseThrow(
                  () ->
                      CustomerInput.invalid(
                          "supersedesRevisionId",
                          FieldErrorCode.INVALID_VALUE,
                          "같은 청구의 사건이어야 합니다."));
      if (target.superseded()) {
        throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      }
      if (target.effectiveDate().isBefore(from)) {
        from = target.effectiveDate();
      }
    }
    Long revisionId =
        fees.insertRevision(
            feeId,
            type.name(),
            effectiveDate,
            amounts,
            reason,
            principal.membershipId(),
            supersedes,
            now);
    refreshSnapshot(fee.contractId(), feeId, now);
    contracts.bumpVersion(fee.contractId(), now);
    ContractRow row = reload(principal, fee.contractId());
    recalculate(row, from, now);
    return new ContractFeeResponse.FeeRevisionResult(
        revisionId, feeId, type.name(), amounts.totalAmount(), row.officeId(), row.version());
  }

  /** 수납·환급·조정 거래다. 오입력은 같은 청구의 대체되지 않은 거래를 대체한다. 환급은 대체되지 않은 순수납액을 넘을 수 없다. */
  @Transactional
  public ContractFeeResponse.FeeTransactionResult transact(
      StaffPrincipal principal, Long feeId, JsonNode body) {
    Instant now = support.now();
    FeeRow fee = lockFee(principal, feeId);
    CustomerInput.requireObject(body, TRANSACTION_KEYS);
    TransactionType type =
        CustomerInput.requiredEnum(body, "transactionType", TransactionType.class);
    long amount = positive(body, "amount");
    LocalDate actualDate = CustomerInput.requiredDate(body, "actualDate");
    if (actualDate.isAfter(ContractSupport.today(now))) {
      throw CustomerInput.invalid("actualDate", FieldErrorCode.OUT_OF_RANGE, "미래 날짜는 입력할 수 없습니다.");
    }
    String memo = CustomerInput.optionalText(body, "memo", TEXT_MAX, true);
    Long supersedes = CustomerInput.optionalLong(body, "supersedesTransactionId");
    String correctionReason = CustomerInput.optionalText(body, "correctionReason", TEXT_MAX, true);
    List<TransactionRow> rows =
        fees.transactions(fee.contractId()).stream().filter(t -> t.feeId().equals(feeId)).toList();
    LocalDate from = actualDate;
    if (supersedes != null) {
      if (correctionReason == null) {
        throw CustomerInput.invalid("correctionReason", FieldErrorCode.REQUIRED, "정정 사유가 필요합니다.");
      }
      TransactionRow target =
          rows.stream()
              .filter(t -> t.id().equals(supersedes))
              .findFirst()
              .orElseThrow(
                  () ->
                      CustomerInput.invalid(
                          "supersedesTransactionId",
                          FieldErrorCode.INVALID_VALUE,
                          "같은 청구의 거래여야 합니다."));
      if (target.superseded()) {
        throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      }
      if (target.actualDate().isBefore(from)) {
        from = target.actualDate();
      }
    } else if (correctionReason != null) {
      throw CustomerInput.invalid(
          "correctionReason", FieldErrorCode.NOT_ALLOWED, "정정 거래에만 사유를 받습니다.");
    }
    if (type == TransactionType.REFUND) {
      long net =
          netReceived(rows.stream().filter(t -> !t.superseded() && !t.id().equals(supersedes)));
      if (amount > net) {
        throw CustomerInput.invalid("amount", FieldErrorCode.OUT_OF_RANGE, "환급액은 순수납액을 넘을 수 없습니다.");
      }
    }
    Long transactionId =
        fees.insertTransaction(
            feeId,
            type.name(),
            amount,
            actualDate,
            memo,
            supersedes,
            correctionReason,
            principal.membershipId(),
            now);
    contracts.bumpVersion(fee.contractId(), now);
    ContractRow row = reload(principal, fee.contractId());
    recalculate(row, from, now);
    return new ContractFeeResponse.FeeTransactionResult(
        transactionId, feeId, type.name(), amount, actualDate, row.officeId(), row.version());
  }

  @Transactional(readOnly = true)
  public ContractFeeResponse.FeeList list(StaffPrincipal principal, Long contractId) {
    ContractRow row = reload(principal, contractId);
    LocalDate today = ContractSupport.today(support.now());
    List<RevisionRow> revisions = fees.revisions(contractId);
    List<TransactionRow> transactions = fees.transactions(contractId);
    List<FeeItem> items = new ArrayList<>();
    Map<Long, FeePayer> payers = new LinkedHashMap<>();
    for (FeeRow fee : fees.fees(contractId)) {
      List<RevisionRow> own = revisions.stream().filter(r -> r.feeId().equals(fee.id())).toList();
      List<TransactionRow> ownTx =
          transactions.stream().filter(t -> t.feeId().equals(fee.id())).toList();
      Amounts current = current(own, today);
      long net = netReceived(ownTx.stream().filter(t -> !t.superseded()));
      items.add(
          new FeeItem(
              fee.id(),
              fee.payerType(),
              fee.payerPartyId(),
              fee.payerName(),
              current.agreedFeeAmount(),
              fee.vatIncluded(),
              current.supplyAmount(),
              current.taxAmount(),
              current.totalAmount(),
              TimeRules.toDisplayOffset(TimeRules.startOfDisplayDay(fee.billedAt())),
              fee.dueAt() == null
                  ? null
                  : TimeRules.toDisplayOffset(TimeRules.startOfDisplayDay(fee.dueAt())),
              net,
              own.stream()
                  .map(
                      r ->
                          new FeeRevisionItem(
                              r.id(),
                              r.revisionType(),
                              r.effectiveDate(),
                              r.amounts().totalAmount(),
                              r.reason(),
                              r.supersedesRevisionId(),
                              r.superseded()))
                  .toList(),
              ownTx.stream()
                  .map(
                      t ->
                          new FeeTransactionItem(
                              t.id(),
                              t.transactionType(),
                              t.amount(),
                              t.actualDate(),
                              t.memo(),
                              t.supersedesTransactionId(),
                              t.correctionReason(),
                              TimeRules.toDisplayOffset(t.recordedAt()),
                              t.superseded()))
                  .toList(),
              row.officeId(),
              row.version()));
      FeePayer before = payers.get(fee.payerPartyId());
      long billed = (before == null ? 0 : before.billedTotal()) + current.totalAmount();
      long received = (before == null ? 0 : before.netReceivedTotal()) + net;
      payers.put(
          fee.payerPartyId(),
          new FeePayer(
              fee.payerPartyId(),
              fee.payerName(),
              fee.payerType(),
              billed,
              received,
              Math.max(billed - received, 0),
              Math.max(received - billed, 0)));
    }
    return new ContractFeeResponse.FeeList(items, new ArrayList<>(payers.values()));
  }

  /** 오늘 효력이 있는 대체되지 않은 최신 사건의 금액이다. 모든 사건이 미래 효력이면 가장 이른 사건이다. */
  static Amounts current(List<RevisionRow> revisions, LocalDate today) {
    List<RevisionRow> valid = revisions.stream().filter(r -> !r.superseded()).toList();
    return valid.stream()
        .filter(r -> !r.effectiveDate().isAfter(today))
        .max(Comparator.comparing(RevisionRow::effectiveDate).thenComparing(RevisionRow::id))
        .or(
            () ->
                valid.stream()
                    .min(
                        Comparator.comparing(RevisionRow::effectiveDate)
                            .thenComparing(RevisionRow::id)))
        .map(RevisionRow::amounts)
        .orElseThrow();
  }

  /** 대체되지 않은 수납에서 환급을 뺀 값이다. 조정 거래는 순수납에 넣지 않는다(월말결산과 같다). */
  private static long netReceived(java.util.stream.Stream<TransactionRow> rows) {
    return rows.mapToLong(
            t ->
                switch (t.transactionType()) {
                  case "RECEIPT" -> t.amount();
                  case "REFUND" -> -t.amount();
                  default -> 0;
                })
        .sum();
  }

  private void refreshSnapshot(Long contractId, Long feeId, Instant now) {
    List<RevisionRow> own =
        fees.revisions(contractId).stream().filter(r -> r.feeId().equals(feeId)).toList();
    fees.updateSnapshot(feeId, current(own, ContractSupport.today(now)));
  }

  /** 보수 기록이 바뀐 날짜의 달부터 이번 달까지와 잔금 예정일의 달을 다시 계산한다. */
  private void recalculate(ContractRow row, LocalDate from, Instant now) {
    List<Instant> times = new ArrayList<>(ContractSupport.monthsFrom(from, now));
    if (row.values().balanceDueDate() != null) {
      times.add(TimeRules.startOfDisplayDay(row.values().balanceDueDate()));
    }
    support.recalculate(row.officeId(), times);
  }

  private FeeRow lockFee(StaffPrincipal principal, Long feeId) {
    return fees.lock(principal.officeId(), feeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private ContractRow reload(StaffPrincipal principal, Long contractId) {
    return contracts
        .find(principal.officeId(), contractId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private static Amounts amounts(JsonNode body) {
    long agreed = nonNegative(body, "agreedFeeAmount");
    long supply = nonNegative(body, "supplyAmount");
    long tax = nonNegative(body, "taxAmount");
    long total = nonNegative(body, "totalAmount");
    if (total != supply + tax) {
      throw new BusinessException(ErrorCode.FEE_AMOUNT_MISMATCH);
    }
    return new Amounts(agreed, supply, tax, total);
  }

  private static long nonNegative(JsonNode body, String key) {
    long value = CustomerInput.requiredLong(body, key);
    if (value < 0) {
      throw CustomerInput.invalid(key, FieldErrorCode.OUT_OF_RANGE, "0 이상이어야 합니다.");
    }
    return value;
  }

  static long positive(JsonNode body, String key) {
    long value = CustomerInput.requiredLong(body, key);
    if (value <= 0) {
      throw CustomerInput.invalid(key, FieldErrorCode.OUT_OF_RANGE, "0보다 커야 합니다.");
    }
    return value;
  }
}
