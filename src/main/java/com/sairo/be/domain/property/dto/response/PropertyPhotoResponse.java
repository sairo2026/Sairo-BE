package com.sairo.be.domain.property.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record PropertyPhotoResponse(
    Long photoId,
    String objectKey,
    @Schema(nullable = true) String description,
    int sortOrder,
    String url,
    OffsetDateTime urlExpiresAt,
    Long officeId,
    OffsetDateTime updatedAt,
    Long version) {}
