package com.sairo.be.domain.contract.entity;

import java.time.LocalDate;

/** 계약 핵심값이다. 초안에서는 거래유형과 담당자 외 값이 비어 있을 수 있다. */
public record ContractValues(
    DealType dealType,
    Long managingMembershipId,
    LocalDate signedDate,
    Long salePrice,
    Long depositAmount,
    Long monthlyRentAmount,
    LocalDate balanceDueDate,
    String balanceDueDatePendingReason,
    LocalDate leaseStartDate,
    LocalDate leaseEndDate,
    String memo) {}
