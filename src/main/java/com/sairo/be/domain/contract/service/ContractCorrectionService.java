package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.dto.response.ContractRevisionResponse;
import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.entity.ContractStatus;
import com.sairo.be.domain.contract.entity.ContractValues;
import com.sairo.be.domain.contract.entity.DealType;
import com.sairo.be.domain.contract.entity.LeasePeriodStatus;
import com.sairo.be.domain.contract.entity.PartyInput;
import com.sairo.be.domain.contract.entity.PartyRow;
import com.sairo.be.domain.contract.entity.PartySide;
import com.sairo.be.domain.contract.entity.StatusHistoryRow;
import com.sairo.be.domain.contract.repository.ContractHistoryRepository;
import com.sairo.be.domain.contract.repository.ContractHistoryRepository.RevisionRow;
import com.sairo.be.domain.contract.repository.ContractPartyRepository;
import com.sairo.be.domain.contract.repository.ContractRepository;
import com.sairo.be.domain.contract.repository.ContractRepository.PropertyRef;
import com.sairo.be.domain.contract.service.ContractInput.Parsed;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 체결 뒤 핵심정보 오기록 정정이다(CONTRACT-03). 이전 버전은 고치지 않고 새 버전을 더하며, 체결일이 바뀌면 계약 트랙 SIGNED 사건을 새 효력시각의 대체
 * 사건으로 바꾸고 영향받은 달의 결산을 다시 계산한다.
 */
@Profile("!migrate")
@Service
public class ContractCorrectionService {

  static final int REASON_MAX = 2_000;

  private final ContractRepository contracts;
  private final ContractPartyRepository parties;
  private final ContractHistoryRepository histories;
  private final ContractSupport support;

  public ContractCorrectionService(
      ContractRepository contracts,
      ContractPartyRepository parties,
      ContractHistoryRepository histories,
      ContractSupport support) {
    this.contracts = contracts;
    this.parties = parties;
    this.histories = histories;
    this.support = support;
  }

  @Transactional
  public ContractRevisionResponse correct(
      StaffPrincipal principal, Long contractId, long expectedVersion, JsonNode body) {
    Instant now = support.now();
    ContractRow row = support.lock(principal.officeId(), contractId);
    Parsed parsed = ContractInput.correction(body, row.values());
    long baseRevisionId = CustomerInput.requiredLong(body, "baseRevisionId");
    String reason = CustomerInput.requiredText(body, "reason", REASON_MAX, true);
    Instant effectiveAt = CustomerInput.requiredDateTime(body, "effectiveAt").toInstant();
    if (effectiveAt.isAfter(now)) {
      throw CustomerInput.invalid("effectiveAt", FieldErrorCode.OUT_OF_RANGE, "효력시각은 미래일 수 없습니다.");
    }
    ContractSupport.requireSignedOnce(row);
    ContractSupport.requireVersion(row, expectedVersion);
    RevisionRow base = histories.latestRevision(contractId).orElseThrow();
    if (base.id() != baseRevisionId) {
      throw new BusinessException(ErrorCode.CONTRACT_REVISION_CONFLICT);
    }
    ContractValues values = parsed.values();
    PropertyRef property =
        contracts.lockProperty(principal.officeId(), row.propertyId()).orElseThrow();
    ContractInput.requireSignable(
        values,
        property.address(),
        parsed.sellers().size(),
        parsed.buyers().size(),
        ContractSupport.today(now));
    if (values.dealType() == DealType.SALE && row.contractStatus() == ContractStatus.TERMINATED) {
      throw new BusinessException(ErrorCode.SALE_CANNOT_TERMINATE);
    }

    List<PartyRow> current = parties.current(contractId);
    Map<Long, PartyRow> byId = new HashMap<>();
    current.forEach(party -> byId.put(party.id(), party));
    Set<Long> kept = new HashSet<>();
    checkParties(PartySide.SELLER_LANDLORD, parsed.sellers(), byId, kept);
    checkParties(PartySide.BUYER_TENANT, parsed.buyers(), byId, kept);
    Set<Long> payers = contracts.feePayerPartyIds(contractId);
    for (PartyRow party : current) {
      if (!kept.contains(party.id()) && payers.contains(party.id())) {
        throw new BusinessException(ErrorCode.INVALID_CONTRACT_PARTY);
      }
    }

    if (row.contractStatus() == ContractStatus.SIGNED) {
      support.requireNoConflict(row.propertyId(), contractId, values);
    }
    StatusHistoryRow signedEvent = replaceSignedEventIfMoved(row, values, reason, principal, now);

    parties.shiftCurrent(contractId);
    for (PartyRow party : current) {
      if (!kept.contains(party.id())) {
        parties.remove(party.id(), now);
      }
    }
    applyParties(
        principal.officeId(), contractId, PartySide.SELLER_LANDLORD, parsed.sellers(), byId, now);
    applyParties(
        principal.officeId(), contractId, PartySide.BUYER_TENANT, parsed.buyers(), byId, now);

    LeasePeriodStatus lease =
        row.contractStatus() == ContractStatus.SIGNED
            ? ContractSupport.leaseStatus(values, ContractSupport.today(now))
            : (values.dealType().isLease() ? row.leasePeriodStatus() : null);
    contracts.updateValues(contractId, values, lease, now);
    contracts.refreshPartySnapshot(contractId);
    if (!Objects.equals(lease, row.leasePeriodStatus())) {
      if (lease != null) {
        histories.insertStatus(
            contractId,
            ContractSupport.TRACK_LEASE,
            row.leasePeriodStatus() == null ? null : row.leasePeriodStatus().name(),
            lease.name(),
            reason,
            principal.membershipId(),
            now,
            null,
            now);
      }
    }

    String snapshot = ContractSupport.snapshot(values, parties.current(contractId));
    Long revisionId =
        histories.insertRevision(
            contractId,
            base.revisionNo() + 1,
            "CORRECTED",
            base.currentSnapshot(),
            snapshot,
            effectiveAt,
            principal.membershipId(),
            reason,
            base.id(),
            now);

    List<Instant> times = new ArrayList<>(ContractSupport.affectedTimes(row.values()));
    times.addAll(ContractSupport.affectedTimes(values));
    if (signedEvent != null) {
      times.add(signedEvent.effectiveAt());
    }
    support.recalculate(principal.officeId(), times);

    ContractRow updated = contracts.find(principal.officeId(), contractId).orElseThrow();
    RevisionRow saved = histories.findRevision(revisionId).orElseThrow();
    return ContractQueryService.revision(saved, updated);
  }

  /** 체결일이 바뀌면 대체되지 않은 SIGNED 사건을 새 효력시각의 대체 사건으로 바꾼다. 바꾸지 않으면 null이다. */
  private StatusHistoryRow replaceSignedEventIfMoved(
      ContractRow row,
      ContractValues values,
      String reason,
      StaffPrincipal principal,
      Instant now) {
    if (Objects.equals(row.values().signedDate(), values.signedDate())) {
      return null;
    }
    Instant newAt = ContractSupport.signedEffectiveAt(values.signedDate());
    List<StatusHistoryRow> events = histories.validContractEvents(row.id());
    StatusHistoryRow signed =
        events.stream()
            .filter(event -> "SIGNED".equals(event.toStatus()))
            .min(Comparator.comparing(StatusHistoryRow::effectiveAt))
            .orElse(null);
    if (signed == null) {
      return null;
    }
    for (StatusHistoryRow event : events) {
      if (!event.id().equals(signed.id()) && event.effectiveAt().isBefore(newAt)) {
        throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      }
    }
    histories.insertStatus(
        row.id(),
        ContractSupport.TRACK_CONTRACT,
        signed.fromStatus(),
        signed.toStatus(),
        reason,
        principal.membershipId(),
        newAt,
        signed.id(),
        now);
    return signed;
  }

  private static void checkParties(
      PartySide side, List<PartyInput> inputs, Map<Long, PartyRow> byId, Set<Long> kept) {
    for (PartyInput input : inputs) {
      if (input.partyId() == null) {
        continue;
      }
      PartyRow existing = byId.get(input.partyId());
      if (existing == null || existing.side() != side || !kept.add(input.partyId())) {
        throw new BusinessException(ErrorCode.INVALID_CONTRACT_PARTY);
      }
    }
  }

  private void applyParties(
      Long officeId,
      Long contractId,
      PartySide side,
      List<PartyInput> inputs,
      Map<Long, PartyRow> byId,
      Instant now) {
    List<PartyInput> ordered = new ArrayList<>(inputs);
    ordered.sort(Comparator.comparingInt(PartyInput::sequenceNo));
    for (int i = 0; i < ordered.size(); i++) {
      PartyInput input = ordered.get(i);
      int index = inputs.indexOf(input);
      String field = side.field() + "[" + index + "].customerId";
      if (input.partyId() == null) {
        Long customerId = support.resolveCustomer(officeId, input.customerId(), field);
        parties.insert(contractId, side, i + 1, input.name(), input.phone(), customerId, now);
      } else {
        PartyRow existing = byId.get(input.partyId());
        Long customerId =
            Objects.equals(existing.customerId(), input.customerId())
                ? existing.customerId()
                : support.resolveCustomer(officeId, input.customerId(), field);
        parties.update(input.partyId(), i + 1, input.name(), input.phone(), customerId);
      }
    }
  }
}
