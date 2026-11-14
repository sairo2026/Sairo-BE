package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;

public record CustomerLinkIssueResponse(
    Long linkId,
    String token,
    String publicUrl,
    OffsetDateTime issuedAt,
    OffsetDateTime expiresAt) {}
