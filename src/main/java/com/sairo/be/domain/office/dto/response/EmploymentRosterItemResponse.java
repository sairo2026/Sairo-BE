package com.sairo.be.domain.office.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Schema(description = "고용명부 항목. 수정 시각과 버전은 사무소 집계의 값이다.")
public record EmploymentRosterItemResponse(
    Long rosterId,
    Long officeId,
    Long membershipId,
    String personName,
    String legalQualification,
    LocalDate employmentStartDate,
    LocalDate employmentEndDate,
    String confirmationSource,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version,
    @JsonInclude(JsonInclude.Include.NON_NULL) String warning) {}
