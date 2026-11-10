package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;

public record CustomerAvailableTimesResponse(
    Long coordinationId, int responseVersion, OffsetDateTime submittedAt) {}
