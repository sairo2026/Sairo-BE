package com.sairo.be.domain.office.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "대표 이전 시작 요청")
public record OwnerTransferCreateRequest(
    @Schema(description = "신규 대표 후보 사용자 ID") @NotNull Long toUserId) {}
