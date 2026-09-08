package com.sairo.be.domain.coordination.dto.request;

import jakarta.validation.constraints.NotNull;

public record CoordinationConfirmRequest(
    @NotNull Long buyerResponseId, @NotNull Long candidateTimeId) {}
