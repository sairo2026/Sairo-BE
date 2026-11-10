package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "구성원 역할 변경 결과")
public record MembershipRoleUpdateResponse(
    Long membershipId,
    String role,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
