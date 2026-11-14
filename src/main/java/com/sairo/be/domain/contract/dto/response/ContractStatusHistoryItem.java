package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "계약 상태·임대기간 상태·잔금 상태 이력 한 건이다(ContractStatusHistoryResponse의 항목).")
public record ContractStatusHistoryItem(
    @Schema(description = "이력 ID. 과거 상태 오입력 정정의 supersedesHistoryId로 쓴다") Long historyId,
    @Schema(allowableValues = {"CONTRACT", "LEASE_PERIOD", "SETTLEMENT"}) String track,
    @Schema(nullable = true) String fromStatus,
    String toStatus,
    @Schema(nullable = true) String reason,
    @Schema(nullable = true) Long changedByMembershipId,
    @Schema(nullable = true) String changedByMemberName,
    OffsetDateTime effectiveAt,
    @Schema(nullable = true) Long supersedesHistoryId,
    OffsetDateTime createdAt) {}
