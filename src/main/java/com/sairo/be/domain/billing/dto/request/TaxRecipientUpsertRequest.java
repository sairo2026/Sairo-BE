package com.sairo.be.domain.billing.dto.request;

import com.sairo.be.domain.billing.entity.TaxType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "세금계산서 수신 정보 저장 요청")
public record TaxRecipientUpsertRequest(
    @NotBlank String businessNumber,
    @NotBlank @Size(max = 100) String companyName,
    @NotBlank @Size(max = 50) String representativeName,
    @NotBlank @Size(max = 254) String receiverEmail,
    @NotNull TaxType taxType,
    @NotNull Boolean issueRequested) {}
