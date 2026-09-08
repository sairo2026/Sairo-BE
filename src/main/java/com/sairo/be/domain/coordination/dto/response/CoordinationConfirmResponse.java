package com.sairo.be.domain.coordination.dto.response;

import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import java.time.Instant;

public record CoordinationConfirmResponse(
    CoordinationStatus status, Instant scheduledAt, Long confirmedBuyerResponseId) {}
