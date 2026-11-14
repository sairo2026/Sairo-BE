package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "소속 처리 결과")
public record MembershipResultResponse(
    Long membershipId,
    String status,
    String role,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
