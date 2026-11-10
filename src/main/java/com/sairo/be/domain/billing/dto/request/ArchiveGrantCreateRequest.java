package com.sairo.be.domain.billing.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "보관권한 발급 요청")
public record ArchiveGrantCreateRequest(
    @NotNull Long officeId,
    @NotNull Long grantedToUserId,
    @NotBlank @Size(max = 200) String contractReference) {}
