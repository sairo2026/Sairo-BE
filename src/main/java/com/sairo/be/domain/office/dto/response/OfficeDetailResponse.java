package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "사무소 기본정보")
public record OfficeDetailResponse(
    Long officeId,
    String name,
    String entityType,
    String registrationAuthority,
    String realEstateLicenseNumber,
    String businessRegistrationNumber,
    String representativeName,
    @Schema(description = "중개보조원에게는 null", nullable = true) String representativePhone,
    String address,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
