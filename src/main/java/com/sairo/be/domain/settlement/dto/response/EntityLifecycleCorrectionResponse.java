package com.sairo.be.domain.settlement.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "매물·고객 수명 사건 효력시각 정정 결과다(EntityLifecycleCorrectionResponse).")
public record EntityLifecycleCorrectionResponse(
    Long eventId, Long supersedesEventId, OffsetDateTime effectiveAt) {}
