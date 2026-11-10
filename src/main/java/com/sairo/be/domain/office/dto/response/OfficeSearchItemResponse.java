package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "참여할 사무소 검색 결과 항목")
public record OfficeSearchItemResponse(
    Long officeId,
    String name,
    String entityType,
    String registrationAuthority,
    String realEstateLicenseNumber,
    String address) {}
