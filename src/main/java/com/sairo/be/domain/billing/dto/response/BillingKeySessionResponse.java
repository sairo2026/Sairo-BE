package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "카드 등록창을 열기 전 발급한 사무소별 고객 키")
public record BillingKeySessionResponse(String customerKey, OffsetDateTime expiresAt) {}
