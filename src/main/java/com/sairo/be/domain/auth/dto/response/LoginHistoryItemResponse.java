package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "로그인 기록 항목")
public record LoginHistoryItemResponse(
    @Schema(description = "로그인 기록 ID") Long loginHistoryId,
    @Schema(description = "로그인 방식 EMAIL/KAKAO") String authMethod,
    @Schema(description = "요청 IP") String ipAddress,
    @Schema(description = "브라우저·OS 요약") String userAgent,
    @Schema(description = "로그인 시도 일시") OffsetDateTime createdAt) {}
