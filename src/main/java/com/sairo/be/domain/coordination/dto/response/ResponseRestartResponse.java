package com.sairo.be.domain.coordination.dto.response;

import java.time.Instant;

public record ResponseRestartResponse(String customerLinkUrl, Instant linkExpiresAt) {}
