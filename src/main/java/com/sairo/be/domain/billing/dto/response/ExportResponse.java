package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "내보내기 진행 상태")
public record ExportResponse(
    Long exportId,
    String status,
    String downloadUrl,
    OffsetDateTime expiresAt,
    OffsetDateTime fileExpiresAt) {}
