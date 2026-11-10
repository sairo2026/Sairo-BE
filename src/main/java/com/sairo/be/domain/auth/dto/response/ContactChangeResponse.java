package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "연락처 변경 응답")
public record ContactChangeResponse(
    @Schema(description = "변경한 연락처 종류 email/phone") String field,
    @Schema(description = "변경된 연락처") String value,
    @Schema(description = "변경 연락처 인증 시각") OffsetDateTime verifiedAt) {}
