package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.dto.response.ContractCreateResponse;
import com.sairo.be.domain.contract.dto.response.ContractRenewResponse;
import com.sairo.be.domain.contract.dto.response.ContractStateResponse;
import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.entity.ContractStatus;
import com.sairo.be.domain.contract.entity.ContractValues;
import com.sairo.be.domain.contract.entity.LeasePeriodStatus;
import com.sairo.be.domain.contract.entity.PartyInput;
import com.sairo.be.domain.contract.entity.PartyRow;
import com.sairo.be.domain.contract.entity.PartySide;
import com.sairo.be.domain.contract.repository.ContractPartyRepository;
import com.sairo.be.domain.contract.repository.ContractRepository;
import com.sairo.be.domain.contract.repository.ContractRepository.PartySnapshot;
import com.sairo.be.domain.contract.repository.ContractRepository.PropertyRef;
import com.sairo.be.domain.contract.service.ContractInput.Parsed;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 계약 초안 저장·수정·삭제·체결, 즉시 체결 등록, 재계약이다(CONTRACT-02·05). */
@Profile("!migrate")
@Service
public class ContractCommandService {

  private final ContractRepository contracts;
  private final ContractPartyRepository parties;
  private final ContractSupport support;

  public ContractCommandService(
      ContractRepository contracts, ContractPartyRepository parties, ContractSupport support) {
    this.contracts = contracts;
    this.parties = parties;
    this.support = support;
  }

  @Transactional
  public ContractStateResponse createDraft(
      StaffPrincipal principal, Long propertyId, JsonNode body) {
    Instant now = support.now();
    Parsed parsed = ContractInput.full(body);
    ContractInput.requireLeaseOrder(parsed.values());
    PropertyRef property = support.lockProperty(principal.officeId(), propertyId);
    support.requireManager(principal.officeId(), parsed.values().managingMembershipId());
    Long id =
        contracts.insert(
            principal.officeId(),
            property.id(),
            null,
            parsed.values(),
            ContractStatus.DRAFT,
            null,
            firstParties(parsed),
            now);
    support.insertParties(
        principal.officeId(), id, PartySide.SELLER_LANDLORD, parsed.sellers(), now);
    support.insertParties(principal.officeId(), id, PartySide.BUYER_TENANT, parsed.buyers(), now);
    contracts.refreshPartySnapshot(id);
    return state(reload(principal, id));
  }

  @Transactional
  public ContractStateResponse updateDraft(
      StaffPrincipal principal, Long contractId, long expectedVersion, JsonNode body) {
    Instant now = support.now();
    ContractRow row = support.lock(principal.officeId(), contractId);
    ContractSupport.requireDraft(row);
    ContractSupport.requireVersion(row, expectedVersion);
    Parsed parsed = ContractInput.patch(body, row.values());
    ContractInput.requireLeaseOrder(parsed.values());
    if (!parsed.values().managingMembershipId().equals(row.values().managingMembershipId())) {
      support.requireManager(principal.officeId(), parsed.values().managingMembershipId());
    }
    if (parsed.sellers() != null) {
      parties.deleteSide(contractId, PartySide.SELLER_LANDLORD);
      support.insertParties(
          principal.officeId(), contractId, PartySide.SELLER_LANDLORD, parsed.sellers(), now);
    }
    if (parsed.buyers() != null) {
      parties.deleteSide(contractId, PartySide.BUYER_TENANT);
      support.insertParties(
          principal.officeId(), contractId, PartySide.BUYER_TENANT, parsed.buyers(), now);
    }
    contracts.updateValues(contractId, parsed.values(), null, now);
    contracts.refreshPartySnapshot(contractId);
    return state(reload(principal, contractId));
  }

  @Transactional
  public void deleteDraft(StaffPrincipal principal, Long contractId) {
    ContractRow row = support.lock(principal.officeId(), contractId);
    ContractSupport.requireDraft(row);
    contracts.delete(contractId);
  }

  @Transactional
  public ContractStateResponse sign(
      StaffPrincipal principal, Long contractId, long expectedVersion) {
    Instant now = support.now();
    ContractRow row = support.lock(principal.officeId(), contractId);
    ContractSupport.requireDraft(row);
    ContractSupport.requireVersion(row, expectedVersion);
    PropertyRef property = support.lockProperty(principal.officeId(), row.propertyId());
    List<PartyRow> current = parties.current(contractId);
    ContractValues values = row.values();
    ContractInput.requireSignable(
        values,
        property.address(),
        (int) ContractSupport.count(current, PartySide.SELLER_LANDLORD),
        (int) ContractSupport.count(current, PartySide.BUYER_TENANT),
        ContractSupport.today(now));
    support.requireManager(principal.officeId(), values.managingMembershipId());
    support.requireNoConflict(row.propertyId(), contractId, values);
    LeasePeriodStatus lease = ContractSupport.leaseStatus(values, ContractSupport.today(now));
    contracts.updateStatus(contractId, ContractStatus.SIGNED, lease, row.settlementStatus(), now);
    ContractRow signed = reload(principal, contractId);
    support.recordSigning(signed, ContractStatus.DRAFT.name(), principal.membershipId(), now);
    return state(signed);
  }

  @Transactional
  public ContractCreateResponse create(StaffPrincipal principal, Long propertyId, JsonNode body) {
    Instant now = support.now();
    Parsed parsed = ContractInput.full(body);
    ContractRow row = createSigned(principal, propertyId, null, parsed, now);
    List<PartyRow> current = parties.current(row.id());
    return new ContractCreateResponse(
        row.id(),
        row.contractStatus().name(),
        ContractSupport.partyResponses(current, PartySide.SELLER_LANDLORD),
        ContractSupport.partyResponses(current, PartySide.BUYER_TENANT),
        row.officeId(),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset(row.updatedAt()),
        row.version());
  }

  /** 전세·월세 재계약이다. 직전 계약은 상태와 값을 바꾸지 않고 새 계약만 previous_contract_id로 잇는다. */
  @Transactional
  public ContractRenewResponse renew(StaffPrincipal principal, Long contractId, JsonNode body) {
    Instant now = support.now();
    Parsed parsed = ContractInput.full(body);
    ContractRow previous =
        contracts
            .find(principal.officeId(), contractId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!previous.dealType().isLease()
        || (previous.contractStatus() != ContractStatus.SIGNED
            && previous.contractStatus() != ContractStatus.ENDED)) {
      throw new BusinessException(ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
    }
    if (!parsed.values().dealType().isLease()) {
      throw CustomerInput.invalid("dealType", FieldErrorCode.NOT_ALLOWED, "재계약은 전세·월세만 할 수 있습니다.");
    }
    ContractRow row = createSigned(principal, previous.propertyId(), previous.id(), parsed, now);
    return new ContractRenewResponse(
        row.id(),
        row.contractStatus().name(),
        row.previousContractId(),
        row.officeId(),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset(row.updatedAt()),
        row.version());
  }

  private ContractRow createSigned(
      StaffPrincipal principal, Long propertyId, Long previousId, Parsed parsed, Instant now) {
    ContractValues values = parsed.values();
    PropertyRef property = support.lockProperty(principal.officeId(), propertyId);
    ContractInput.requireSignable(
        values,
        property.address(),
        parsed.sellers() == null ? 0 : parsed.sellers().size(),
        parsed.buyers() == null ? 0 : parsed.buyers().size(),
        ContractSupport.today(now));
    support.requireManager(principal.officeId(), values.managingMembershipId());
    support.requireNoConflict(property.id(), null, values);
    LeasePeriodStatus lease = ContractSupport.leaseStatus(values, ContractSupport.today(now));
    Long id =
        contracts.insert(
            principal.officeId(),
            property.id(),
            previousId,
            values,
            ContractStatus.SIGNED,
            lease,
            firstParties(parsed),
            now);
    support.insertParties(
        principal.officeId(), id, PartySide.SELLER_LANDLORD, parsed.sellers(), now);
    support.insertParties(principal.officeId(), id, PartySide.BUYER_TENANT, parsed.buyers(), now);
    contracts.refreshPartySnapshot(id);
    ContractRow row = reload(principal, id);
    support.recordSigning(row, null, principal.membershipId(), now);
    return row;
  }

  private static PartySnapshot firstParties(Parsed parsed) {
    PartyInput seller =
        parsed.sellers() == null || parsed.sellers().isEmpty() ? null : parsed.sellers().get(0);
    PartyInput buyer =
        parsed.buyers() == null || parsed.buyers().isEmpty() ? null : parsed.buyers().get(0);
    return new PartySnapshot(
        seller == null ? null : seller.name(),
        seller == null ? null : seller.phone(),
        buyer == null ? null : buyer.name(),
        buyer == null ? null : buyer.phone());
  }

  private ContractRow reload(StaffPrincipal principal, Long contractId) {
    return contracts.find(principal.officeId(), contractId).orElseThrow();
  }

  static ContractStateResponse state(ContractRow row) {
    return new ContractStateResponse(
        row.id(),
        row.contractStatus().name(),
        row.version(),
        row.officeId(),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset(row.updatedAt()));
  }
}
