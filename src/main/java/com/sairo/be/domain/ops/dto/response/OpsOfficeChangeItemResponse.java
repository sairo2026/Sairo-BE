package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Map;

@Schema(description = "사무소 정보 변경 신청 목록 항목")
public record OpsOfficeChangeItemResponse(
    Long requestId,
    Long officeId,
    Long requestedByMembershipId,
    String changeType,
    Map<String, String> requestedValues,
    String status,
    OffsetDateTime reviewedAt,
    String rejectionReason,
    OffsetDateTime createdAt) {}
