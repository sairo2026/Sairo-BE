package com.sairo.be.domain.office.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "대표 이전 거절 요청")
public record OwnerTransferRejectRequest(
    @Schema(description = "거절 사유(선택)") @PlainText(min = 0, max = 500) String reason) {}
