package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "사무소 정보 변경 신청 처리 결과")
public record OpsOfficeChangeResultResponse(
    Long requestId,
    String status,
    OffsetDateTime reviewedAt,
    Long officeId,
    OffsetDateTime updatedAt,
    Long version) {}
