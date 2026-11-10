package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;

public record CustomerLinkReissueResponse(
    Long linkId,
    String token,
    String publicUrl,
    OffsetDateTime issuedAt,
    OffsetDateTime expiresAt) {}
