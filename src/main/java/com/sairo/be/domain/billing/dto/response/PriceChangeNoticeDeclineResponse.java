package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "가격 인상 안내 거절 결과")
public record PriceChangeNoticeDeclineResponse(
    Long noticeId, String decision, OffsetDateTime decidedAt, OffsetDateTime subscriptionEndsAt) {}
