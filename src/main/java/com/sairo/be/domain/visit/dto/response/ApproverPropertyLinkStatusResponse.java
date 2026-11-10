package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;

public record ApproverPropertyLinkStatusResponse(
    boolean hasActiveLink,
    Long linkId,
    String approvalRole,
    OffsetDateTime issuedAt,
    OffsetDateTime expiresAt,
    OffsetDateTime lastAccessedAt) {}
