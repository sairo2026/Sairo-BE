package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사무소 존재 여부 조회 응답")
public record OfficeLookupResponse(
    @Schema(description = "이미 등록된 사무소의 ID, 없으면 null", nullable = true) Long existingOfficeId) {}
