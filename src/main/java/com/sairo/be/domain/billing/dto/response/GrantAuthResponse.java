package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "반환·보관권한 재인증 결과")
public record GrantAuthResponse(Long grantId, String grantType, OffsetDateTime expiresAt) {}
