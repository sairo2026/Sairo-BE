package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "표시 이름 수정 응답")
public record MeUpdateResponse(
    Long userId,
    String displayName,
    String email,
    boolean emailVerified,
    String phone,
    boolean phoneVerified,
    boolean kakaoLinked,
    String accountStatus,
    OffsetDateTime createdAt) {}
