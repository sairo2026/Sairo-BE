package com.sairo.be.domain.office.dto.request;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "구성원 역할 변경 요청")
public record MembershipRoleUpdateRequest(
    @Schema(description = "AGENT 또는 DEPUTY") @NotNull OfficeMembershipRole role) {}
