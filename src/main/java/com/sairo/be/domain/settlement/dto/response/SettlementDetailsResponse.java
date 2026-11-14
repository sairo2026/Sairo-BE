package com.sairo.be.domain.settlement.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "지표별 원본 레코드다(SettlementDetailsResponse). items의 필드는 지표마다 다르다.")
public record SettlementDetailsResponse(
    String metric,
    String yearMonth,
    List<Map<String, Object>> items,
    int page,
    int size,
    long totalElements) {}
