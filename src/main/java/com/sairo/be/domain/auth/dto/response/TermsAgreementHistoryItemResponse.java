package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "약관 동의·철회 이력 항목")
public record TermsAgreementHistoryItemResponse(
    Long agreementId,
    String termsCode,
    String termsName,
    String version,
    boolean agreed,
    String consentPath,
    OffsetDateTime recordedAt) {}
