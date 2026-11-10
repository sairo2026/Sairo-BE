package com.sairo.be.domain.office.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "사무소 등록 신청 작성 응답")
public record OfficeRegistrationCreateResponse(
    Long registrationId,
    String status,
    int currentRevisionNo,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version,
    @JsonInclude(JsonInclude.Include.NON_NULL) String warning) {}
