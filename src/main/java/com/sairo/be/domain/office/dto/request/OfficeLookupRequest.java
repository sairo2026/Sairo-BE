package com.sairo.be.domain.office.dto.request;

import com.sairo.be.domain.office.entity.OfficeEntityType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "사무소 존재 여부 조회 조건")
public record OfficeLookupRequest(
    @Schema(description = "사업자 유형") @NotNull OfficeEntityType entityType,
    @Schema(description = "개설등록관청") @NotBlank String registrationAuthority,
    @Schema(description = "개설등록번호") @NotBlank String realEstateLicenseNumber) {}
