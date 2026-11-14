package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.dto.response.ContractDuplicateExclusionResponse;
import com.sairo.be.domain.contract.dto.response.ContractSettlementStatusResponse;
import com.sairo.be.domain.contract.dto.response.ContractStateResponse;
import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.entity.ContractStatus;
import com.sairo.be.domain.contract.entity.DealType;
import com.sairo.be.domain.contract.entity.SettlementStatus;
import com.sairo.be.domain.contract.entity.StatusHistoryRow;
import com.sairo.be.domain.contract.repository.ContractHistoryRepository;
import com.sairo.be.domain.contract.repository.ContractRepository;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 체결 뒤 계약 상태(종료·해제·취소·무효)와 잔금 상태 기록, 대표의 중복 제외다(CONTRACT-03). 상태 이력은 고치지 않고 더하며 과거 오입력은 대체 사건으로
 * 바로잡는다.
 */
@Profile("!migrate")
@Service
public class ContractStatusService {

  static final int REASON_MAX = 2_000;
  private static final Set<String> STATUS_KEYS =
      Set.of("toStatus", "reason", "effectiveAt", "previousStatus", "supersedesHistoryId");
  private static final Set<String> SETTLEMENT_KEYS = Set.of("toStatus", "reason", "effectiveAt");
  private static final Set<String> EXCLUSION_KEYS = Set.of("duplicateOfContractId", "reason");
  private static final Set<ContractStatus> CLOSING =
      EnumSet.of(
          ContractStatus.ENDED,
          ContractStatus.TERMINATED,
          ContractStatus.CANCELLED,
          ContractStatus.VOID);

  private final ContractRepository contracts;
  private final ContractHistoryRepository histories;
  private final ContractSupport support;

  public ContractStatusService(
      ContractRepository contracts, ContractHistoryRepository histories, ContractSupport support) {
    this.contracts = contracts;
    this.histories = histories;
    this.support = support;
  }

  @Transactional
  public ContractStateResponse changeStatus(
      StaffPrincipal principal, Long contractId, long expectedVersion, JsonNode body) {
    Instant now = support.now();
    CustomerInput.requireObject(body, STATUS_KEYS);
    ContractStatus to = CustomerInput.requiredEnum(body, "toStatus", ContractStatus.class);
    if (!CLOSING.contains(to)) {
      throw CustomerInput.invalid(
          "toStatus", FieldErrorCode.NOT_ALLOWED, "ENDED, TERMINATED, CANCELLED, VOID 중 하나여야 합니다.");
    }
    String reason = CustomerInput.requiredText(body, "reason", REASON_MAX, true);
    Instant effectiveAt = notFuture(CustomerInput.requiredDateTime(body, "effectiveAt"), now);
    ContractStatus previous =
        CustomerInput.isNull(body, "previousStatus")
            ? null
            : CustomerInput.enumValue(body, "previousStatus", ContractStatus.class);
    if (to == ContractStatus.VOID && previous == null) {
      throw CustomerInput.invalid(
          "previousStatus", FieldErrorCode.REQUIRED, "무효 정정에는 이전 상태가 필요합니다.");
    }
    Long supersedesId = CustomerInput.optionalLong(body, "supersedesHistoryId");

    ContractRow row = support.lock(principal.officeId(), contractId);
    ContractSupport.requireVersion(row, expectedVersion);
    ContractSupport.requireSignedOnce(row);
    if (row.dealType() == DealType.SALE && to == ContractStatus.TERMINATED) {
      throw new BusinessException(ErrorCode.SALE_CANNOT_TERMINATE);
    }

    List<StatusHistoryRow> events = histories.validContractEvents(contractId);
    StatusHistoryRow target = null;
    ContractStatus from = row.contractStatus();
    List<Instant> times = new ArrayList<>(ContractSupport.affectedTimes(row.values()));
    times.add(effectiveAt);
    if (supersedesId != null) {
      target =
          histories
              .findStatus(contractId, supersedesId)
              .filter(found -> ContractSupport.TRACK_CONTRACT.equals(found.track()))
              .orElseThrow(
                  () ->
                      CustomerInput.invalid(
                          "supersedesHistoryId",
                          FieldErrorCode.INVALID_VALUE,
                          "이 계약의 계약 상태 이력이 아닙니다."));
      StatusHistoryRow latest = events.isEmpty() ? null : events.get(events.size() - 1);
      if (histories.isSuperseded(target.id())
          || latest == null
          || !latest.id().equals(target.id())
          || "SIGNED".equals(target.toStatus())) {
        throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      }
      from = ContractStatus.valueOf(target.fromStatus());
      times.add(target.effectiveAt());
    }
    if (!allowed(from, to)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (to == ContractStatus.VOID && previous != from) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    for (StatusHistoryRow event : events) {
      if (target == null || !event.id().equals(target.id())) {
        if (effectiveAt.isBefore(event.effectiveAt())) {
          throw new BusinessException(ErrorCode.INVALID_TRANSITION);
        }
      }
    }

    histories.insertStatus(
        contractId,
        ContractSupport.TRACK_CONTRACT,
        from.name(),
        to.name(),
        reason,
        principal.membershipId(),
        effectiveAt,
        target == null ? null : target.id(),
        now);
    contracts.updateStatus(contractId, to, row.leasePeriodStatus(), row.settlementStatus(), now);
    support.recalculate(principal.officeId(), times);
    support.syncFollowups(principal.officeId(), contractId, now);
    return ContractCommandService.state(reload(principal, contractId));
  }

  /** 체결 뒤 전이다. 체결에서 종료·해제·취소·무효로 가고, 무효는 종료·해제·취소 뒤에도 오기록 정정으로 쓸 수 있다. */
  private static boolean allowed(ContractStatus from, ContractStatus to) {
    if (from == ContractStatus.SIGNED) {
      return true;
    }
    return to == ContractStatus.VOID && CLOSING.contains(from) && from != ContractStatus.VOID;
  }

  @Transactional
  public ContractSettlementStatusResponse changeSettlementStatus(
      StaffPrincipal principal, Long contractId, long expectedVersion, JsonNode body) {
    Instant now = support.now();
    CustomerInput.requireObject(body, SETTLEMENT_KEYS);
    SettlementStatus to = CustomerInput.requiredEnum(body, "toStatus", SettlementStatus.class);
    if (to == SettlementStatus.UNDECIDED) {
      throw CustomerInput.invalid("toStatus", FieldErrorCode.NOT_ALLOWED, "UNDECIDED는 지정할 수 없습니다.");
    }
    String reason = CustomerInput.optionalText(body, "reason", REASON_MAX, true);
    OffsetDateTime requested = CustomerInput.optionalDateTime(body, "effectiveAt");
    Instant effectiveAt = requested == null ? now : notFuture(requested, now);

    ContractRow row = support.lock(principal.officeId(), contractId);
    ContractSupport.requireVersion(row, expectedVersion);
    ContractSupport.requireSignedOnce(row);
    if (row.settlementStatus() == to) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    histories.insertStatus(
        contractId,
        ContractSupport.TRACK_SETTLEMENT,
        row.settlementStatus().name(),
        to.name(),
        reason,
        principal.membershipId(),
        effectiveAt,
        null,
        now);
    contracts.updateStatus(contractId, row.contractStatus(), row.leasePeriodStatus(), to, now);
    ContractRow updated = reload(principal, contractId);
    return new ContractSettlementStatusResponse(
        updated.id(),
        updated.settlementStatus().name(),
        updated.version(),
        updated.officeId(),
        TimeRules.toDisplayOffset(updated.createdAt()),
        TimeRules.toDisplayOffset(updated.updatedAt()));
  }

  @Transactional
  public ContractDuplicateExclusionResponse excludeDuplicate(
      StaffPrincipal principal, Long contractId, long expectedVersion, JsonNode body) {
    Instant now = support.now();
    CustomerInput.requireObject(body, EXCLUSION_KEYS);
    Long duplicateOf = CustomerInput.requiredLong(body, "duplicateOfContractId");
    String reason = CustomerInput.requiredText(body, "reason", REASON_MAX, true);
    ContractRow row = support.lock(principal.officeId(), contractId);
    ContractSupport.requireVersion(row, expectedVersion);
    if (row.isDraft() || row.duplicateExcludedAt() != null) {
      throw new BusinessException(ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
    }
    if (duplicateOf.equals(contractId) || !contracts.exists(principal.officeId(), duplicateOf)) {
      throw CustomerInput.invalid(
          "duplicateOfContractId", FieldErrorCode.INVALID_VALUE, "같은 사무소의 다른 계약이어야 합니다.");
    }
    contracts.exclude(contractId, duplicateOf, reason, principal.membershipId(), now);
    List<Instant> times = new ArrayList<>(ContractSupport.affectedTimes(row.values()));
    histories.validContractEvents(contractId).forEach(event -> times.add(event.effectiveAt()));
    support.recalculate(principal.officeId(), times);
    support.syncFollowups(principal.officeId(), contractId, now);
    ContractRow updated = reload(principal, contractId);
    return new ContractDuplicateExclusionResponse(
        updated.id(),
        updated.duplicateOfContractId(),
        TimeRules.toDisplayOffset(updated.duplicateExcludedAt()),
        updated.officeId(),
        updated.version());
  }

  private static Instant notFuture(OffsetDateTime value, Instant now) {
    Instant instant = value.toInstant();
    if (instant.isAfter(now)) {
      throw CustomerInput.invalid("effectiveAt", FieldErrorCode.OUT_OF_RANGE, "효력시각은 미래일 수 없습니다.");
    }
    return instant;
  }

  private ContractRow reload(StaffPrincipal principal, Long contractId) {
    return contracts.find(principal.officeId(), contractId).orElseThrow();
  }
}
