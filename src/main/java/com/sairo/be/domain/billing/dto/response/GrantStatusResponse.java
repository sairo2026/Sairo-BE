package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "반환·보관권한 상태")
public record GrantStatusResponse(Long grantId, OffsetDateTime expiresAt, boolean revoked) {}
