package com.sairo.be.domain.office.dto.request;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "구성원 초대 요청")
public record MembershipInviteRequest(
    @Schema(description = "사이로에 가입된 계정의 이메일") @NotBlank @Email @Size(max = 254) String email,
    @Schema(description = "AGENT 또는 DEPUTY") @NotNull OfficeMembershipRole role) {}
