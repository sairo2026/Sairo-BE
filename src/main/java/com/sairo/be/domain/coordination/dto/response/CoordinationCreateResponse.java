package com.sairo.be.domain.coordination.dto.response;

import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import java.time.Instant;

public record CoordinationCreateResponse(
    Long coordinationId,
    CoordinationStatus status,
    Long tenantResponseId,
    String customerLinkUrl,
    Instant linkExpiresAt) {}
