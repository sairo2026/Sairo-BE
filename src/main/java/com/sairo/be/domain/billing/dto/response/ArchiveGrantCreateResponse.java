package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "보관권한 발급 결과")
public record ArchiveGrantCreateResponse(Long grantId, OffsetDateTime expiresAt, boolean revoked) {}
