package com.sairo.be.domain.office.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "거절, 정지, 회수 사유")
public record MembershipReasonRequest(
    @Schema(description = "사유") @NotBlank @PlainText(max = 1000) String reason) {}
