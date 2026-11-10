package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "사무소 정보 변경 반려 요청")
public record OpsOfficeChangeRejectRequest(
    @Schema(description = "고객에게 공개되는 반려 사유") @NotBlank @PlainText(max = 1000) String reason) {}
