package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.dto.response.ContractDetailResponse;
import com.sairo.be.domain.contract.dto.response.ContractListItem;
import com.sairo.be.domain.contract.dto.response.ContractRevisionResponse;
import com.sairo.be.domain.contract.dto.response.ContractStatusHistoryItem;
import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.entity.ContractStatus;
import com.sairo.be.domain.contract.entity.ContractValues;
import com.sairo.be.domain.contract.entity.PartyRow;
import com.sairo.be.domain.contract.entity.PartySide;
import com.sairo.be.domain.contract.entity.StatusHistoryRow;
import com.sairo.be.domain.contract.repository.ContractHistoryRepository;
import com.sairo.be.domain.contract.repository.ContractHistoryRepository.RevisionRow;
import com.sairo.be.domain.contract.repository.ContractPartyRepository;
import com.sairo.be.domain.contract.repository.ContractReadRepository;
import com.sairo.be.domain.contract.repository.ContractReadRepository.Header;
import com.sairo.be.domain.contract.repository.ContractReadRepository.ListFilter;
import com.sairo.be.domain.contract.repository.ContractReadRepository.ListRow;
import com.sairo.be.domain.contract.repository.ContractRepository;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 계약 목록·상세·버전 이력·상태 이력 조회다(CONTRACT-01·03). */
@Profile("!migrate")
@Service
public class ContractQueryService {

  private static final int SEARCH_MAX = 100;

  private final ContractRepository contracts;
  private final ContractReadRepository reads;
  private final ContractPartyRepository parties;
  private final ContractHistoryRepository histories;
  private final Clock clock;

  public ContractQueryService(
      ContractRepository contracts,
      ContractReadRepository reads,
      ContractPartyRepository parties,
      ContractHistoryRepository histories,
      Clock clock) {
    this.contracts = contracts;
    this.reads = reads;
    this.parties = parties;
    this.histories = histories;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<ContractListItem> list(
      StaffPrincipal principal,
      String contractStatus,
      String propertyAddress,
      String partyName,
      Boolean includeExcluded,
      Integer leaseEndWithinDays,
      PageQuery page) {
    String status = null;
    if (contractStatus != null) {
      try {
        status = ContractStatus.valueOf(contractStatus).name();
      } catch (IllegalArgumentException e) {
        throw CustomerInput.invalid("contractStatus", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
      }
    }
    if (leaseEndWithinDays != null && leaseEndWithinDays < 1) {
      throw CustomerInput.invalid(
          "leaseEndWithinDays", FieldErrorCode.OUT_OF_RANGE, "1 이상의 정수여야 합니다.");
    }
    ListFilter filter =
        new ListFilter(
            principal.officeId(),
            status,
            keyword("propertyAddress", propertyAddress),
            keyword("partyName", partyName),
            Boolean.TRUE.equals(includeExcluded),
            leaseEndWithinDays,
            TimeRules.displayDate(clock.instant()));
    long total = reads.count(filter);
    List<ContractListItem> items =
        reads.list(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(ContractQueryService::listItem)
            .toList();
    return PageResponse.of(items, page.page(), page.size(), total);
  }

  private static String keyword(String field, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String stripped = value.strip();
    if (stripped.length() > SEARCH_MAX) {
      throw CustomerInput.invalid(field, FieldErrorCode.TOO_LONG, "100자 이하로 입력해야 합니다.");
    }
    return stripped;
  }

  private static ContractListItem listItem(ListRow row) {
    ContractRow c = row.contract();
    List<String> missing = new ArrayList<>();
    if (!c.isDraft()) {
      if (!row.salesContractAttached()) {
        missing.add("SALES_CONTRACT");
      }
      if (!row.disclosureAttached()) {
        missing.add("DISCLOSURE_STATEMENT");
      }
    }
    return new ContractListItem(
        c.id(),
        row.propertyAddress(),
        c.dealType().name(),
        c.contractStatus().name(),
        c.leasePeriodStatus() == null ? null : c.leasePeriodStatus().name(),
        c.settlementStatus().name(),
        c.values().signedDate(),
        row.sellerName(),
        row.sellerCount(),
        row.buyerName(),
        row.buyerCount(),
        c.duplicateExcludedAt() != null,
        c.dealType().isLease() ? c.values().leaseEndDate() : null,
        c.values().managingMembershipId(),
        row.managerName(),
        missing,
        c.officeId(),
        TimeRules.toDisplayOffset(c.createdAt()),
        TimeRules.toDisplayOffset(c.updatedAt()),
        c.version());
  }

  @Transactional(readOnly = true)
  public ContractDetailResponse detail(StaffPrincipal principal, Long contractId) {
    ContractRow c = require(principal, contractId);
    ContractValues v = c.values();
    Header header = reads.header(contractId);
    List<PartyRow> current = parties.current(contractId);
    Long revisionId = histories.latestRevision(contractId).map(RevisionRow::id).orElse(null);
    List<ContractDetailResponse.Document> documents =
        reads.documents(contractId).stream()
            .map(
                d ->
                    new ContractDetailResponse.Document(
                        d.id(),
                        d.documentType(),
                        d.versionNo(),
                        d.contractRevisionId(),
                        d.writtenDate(),
                        d.deliveredDate(),
                        d.datePendingReason(),
                        d.retentionYears(),
                        d.retentionEndDate(),
                        d.piiMaskingConfirmed(),
                        d.supersedesDocumentId()))
            .toList();
    boolean excluded = c.duplicateExcludedAt() != null;
    return new ContractDetailResponse(
        c.id(),
        c.propertyId(),
        header.propertyAddress(),
        c.dealType().name(),
        c.contractStatus().name(),
        c.leasePeriodStatus() == null ? null : c.leasePeriodStatus().name(),
        c.settlementStatus().name(),
        v.managingMembershipId(),
        header.managerName(),
        v.signedDate(),
        v.salePrice(),
        v.depositAmount(),
        v.monthlyRentAmount(),
        v.balanceDueDate(),
        v.balanceDueDatePendingReason(),
        v.leaseStartDate(),
        v.leaseEndDate(),
        c.previousContractId(),
        revisionId,
        v.memo(),
        excluded,
        excluded ? c.duplicateOfContractId() : null,
        excluded ? TimeRules.toDisplayOffset(c.duplicateExcludedAt()) : null,
        excluded ? c.duplicateExclusionReason() : null,
        ContractSupport.partyResponses(current, PartySide.SELLER_LANDLORD),
        ContractSupport.partyResponses(current, PartySide.BUYER_TENANT),
        documents,
        c.officeId(),
        TimeRules.toDisplayOffset(c.createdAt()),
        TimeRules.toDisplayOffset(c.updatedAt()),
        c.version());
  }

  @Transactional(readOnly = true)
  public PageResponse<ContractRevisionResponse> revisions(
      StaffPrincipal principal, Long contractId, PageQuery page) {
    ContractRow c = require(principal, contractId);
    long total = histories.countRevisions(contractId);
    List<ContractRevisionResponse> items =
        histories.pageRevisions(contractId, page.size(), (long) page.page() * page.size()).stream()
            .map(row -> revision(row, c))
            .toList();
    return PageResponse.of(items, page.page(), page.size(), total);
  }

  @Transactional(readOnly = true)
  public PageResponse<ContractStatusHistoryItem> statusHistory(
      StaffPrincipal principal, Long contractId, PageQuery page) {
    require(principal, contractId);
    long total = histories.countStatus(contractId);
    List<ContractStatusHistoryItem> items =
        histories.pageStatus(contractId, page.size(), (long) page.page() * page.size()).stream()
            .map(
                view -> {
                  StatusHistoryRow h = view.row();
                  return new ContractStatusHistoryItem(
                      h.id(),
                      h.track(),
                      h.fromStatus(),
                      h.toStatus(),
                      h.reason(),
                      h.changedByMembershipId(),
                      view.changedByName(),
                      TimeRules.toDisplayOffset(h.effectiveAt()),
                      h.supersedesHistoryId(),
                      TimeRules.toDisplayOffset(h.createdAt()));
                })
            .toList();
    return PageResponse.of(items, page.page(), page.size(), total);
  }

  private ContractRow require(StaffPrincipal principal, Long contractId) {
    return contracts
        .find(principal.officeId(), contractId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  static ContractRevisionResponse revision(RevisionRow row, ContractRow contract) {
    return new ContractRevisionResponse(
        row.id(),
        row.revisionNo(),
        row.revisionType(),
        contract.id(),
        ContractSupport.json(row.previousSnapshot()),
        ContractSupport.json(row.currentSnapshot()),
        row.reason(),
        TimeRules.toDisplayOffset(row.effectiveAt()),
        TimeRules.toDisplayOffset(row.recordedAt()),
        row.recordedByMembershipId(),
        row.supersedesRevisionId(),
        contract.version(),
        contract.officeId());
  }
}
