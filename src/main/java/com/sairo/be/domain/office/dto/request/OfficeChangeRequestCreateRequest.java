package com.sairo.be.domain.office.dto.request;

import com.sairo.be.domain.office.entity.OfficeChangeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

@Schema(description = "사무소 정보 변경 신청 요청")
public record OfficeChangeRequestCreateRequest(
    @Schema(description = "변경 종류") @NotNull OfficeChangeType changeType,
    @Schema(description = "변경 필드별 신청 값") @NotEmpty Map<String, String> requestedValues) {}
