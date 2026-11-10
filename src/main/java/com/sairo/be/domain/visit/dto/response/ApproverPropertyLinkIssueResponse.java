package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;

public record ApproverPropertyLinkIssueResponse(
    Long linkId,
    Long approvalId,
    String approvalRole,
    String token,
    String publicUrl,
    OffsetDateTime issuedAt,
    OffsetDateTime expiresAt) {}
