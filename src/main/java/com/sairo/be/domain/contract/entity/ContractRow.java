package com.sairo.be.domain.contract.entity;

import java.time.Instant;

/** contract 표 한 행이다. */
public record ContractRow(
    Long id,
    Long officeId,
    Long propertyId,
    Long previousContractId,
    ContractValues values,
    ContractStatus contractStatus,
    LeasePeriodStatus leasePeriodStatus,
    SettlementStatus settlementStatus,
    Long duplicateOfContractId,
    Instant duplicateExcludedAt,
    String duplicateExclusionReason,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public DealType dealType() {
    return values.dealType();
  }

  public boolean isDraft() {
    return contractStatus == ContractStatus.DRAFT;
  }
}
