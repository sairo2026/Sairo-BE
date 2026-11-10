package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "운영자 세션 활성화 응답")
public record OpsMfaVerifyResponse(
    Long operatorId,
    String name,
    List<String> roles,
    OffsetDateTime idleExpiresAt,
    OffsetDateTime absoluteExpiresAt) {}
