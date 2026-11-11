package com.sairo.be.domain.settlement.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "한 달 월말결산이다(MonthlySettlementResponse). 11개 지표군의 18개 세부 값과 부가 표시값을 담는다.")
public record MonthlySettlementResponse(
    String yearMonth,
    @JsonProperty("isFinalized") boolean isFinalized,
    long propertyCountTotal,
    long customerCountTotal,
    long propertyCountNew,
    long customerCountNew,
    long firstInquiryCount,
    long visitCompletedCount,
    long visitCompletedPropertyCount,
    long coordinationEndedCount,
    long coordinationVisitCompletedCount,
    long coordinationVisitIncompleteCount,
    long contractSignedCount,
    long contractSignedPropertyCount,
    long leaseMaturityCount,
    long leaseMaturityPropertyCount,
    long agreedFeeTotal,
    long actualFeeReceivedTotal,
    long outstandingAmount,
    long overcollectedAmount,
    long propertyCountNewMigrated,
    long customerCountNewMigrated,
    long agreedFeeUndatedCount,
    @Schema(description = "집계 기준 시각. 현재 달은 요청 처리 시각, 지난 달은 집계 생성 시각") OffsetDateTime asOf) {}
