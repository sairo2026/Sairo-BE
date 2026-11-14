package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "카드 등록 결과. 빌링키와 카드 원문은 돌려주지 않는다.")
public record BillingKeyRegisterResponse(
    boolean registered, OffsetDateTime registeredAt, OffsetDateTime firstBillingScheduledAt) {}
