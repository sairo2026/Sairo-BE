package com.sairo.be.domain.settlement.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(
    description =
        "월간 메모다(MonthlyMemoResponse, MonthlyMemoUpsertResponse). 작성 전 달은 content가 null이고 version이 0이다.")
public record MonthlyMemoResponse(
    String yearMonth,
    String content,
    long version,
    Long updatedByMembershipId,
    String updatedByMemberName,
    OffsetDateTime updatedAt) {}
