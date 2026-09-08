package com.sairo.be.domain.coordination.dto.response;

import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import java.time.Instant;

public record BuyerCreateResponse(
    Long buyerResponseId,
    String customerLinkUrl,
    Instant linkExpiresAt,
    CoordinationStatus coordinationStatus) {}
