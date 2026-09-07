package com.sairo.be.domain.coordination.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record CoordinationCreateRequest(
    @NotBlank String tenantName,
    @NotBlank String tenantPhone,
    @NotEmpty @Size(max = 10) List<@Valid CandidateTime> candidateTimes) {

  public record CandidateTime(@NotNull Instant startsAt) {}
}
