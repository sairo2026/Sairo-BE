package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "내보내기 진행 상태")
public record ExportResponse(
    Long exportId,
    String status,
    @Schema(nullable = true) String downloadUrl,
    @Schema(nullable = true) OffsetDateTime expiresAt,
    OffsetDateTime fileExpiresAt,
    @Schema(description = "생성 실패 사유. FAILED일 때만 값이 있다", nullable = true) String failureReason) {}
