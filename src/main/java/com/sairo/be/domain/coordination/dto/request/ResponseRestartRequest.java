package com.sairo.be.domain.coordination.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ResponseRestartRequest(@NotEmpty List<@NotNull Long> candidateTimeIds) {}
