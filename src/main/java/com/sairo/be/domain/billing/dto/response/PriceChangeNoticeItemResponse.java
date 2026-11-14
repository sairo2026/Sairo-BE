package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "가격 변경 안내 목록 항목")
public record PriceChangeNoticeItemResponse(
    Long noticeId,
    String changeKind,
    String fromPriceVersion,
    String toPriceVersion,
    int memberCount,
    long currentAmountInclVat,
    long newAmountInclVat,
    OffsetDateTime notifiedAt,
    OffsetDateTime effectiveAt,
    OffsetDateTime appliesFromPeriodStart,
    OffsetDateTime responseDueAt,
    String decision,
    OffsetDateTime decidedAt,
    boolean consentRequired) {}
