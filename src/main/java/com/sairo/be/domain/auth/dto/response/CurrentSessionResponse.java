package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "현재 세션 확인 응답")
public record CurrentSessionResponse(
    @Schema(description = "최초 로그인 시각") OffsetDateTime authenticatedAt,
    @Schema(description = "마지막 세션 사용 시각") OffsetDateTime lastAccessedAt,
    @Schema(description = "idle 만료 예정 시각") OffsetDateTime idleExpiresAt,
    @Schema(description = "absolute 만료 예정 시각") OffsetDateTime absoluteExpiresAt,
    @Schema(description = "재인증 완료 상태가 살아있는지") boolean stepUpValid,
    @Schema(description = "재인증 유효 만료 시각") OffsetDateTime stepUpExpiresAt) {}
