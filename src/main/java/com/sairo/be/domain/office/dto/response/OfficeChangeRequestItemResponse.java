package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Map;

@Schema(description = "사무소 정보 변경 신청 목록 항목")
public record OfficeChangeRequestItemResponse(
    Long requestId,
    String changeType,
    Map<String, String> requestedValues,
    String status,
    String rejectionReason,
    OffsetDateTime createdAt) {}
