package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "광고성 수신 거부 처리 응답")
public record MarketingUnsubscribeResponse(
    @Schema(description = "UNSUBSCRIBED 또는 ALREADY_UNSUBSCRIBED") String status,
    @Schema(description = "이용자가 의사를 표시한 시각") OffsetDateTime expressedAt,
    @Schema(description = "서버가 처리한 시각") OffsetDateTime processedAt,
    @Schema(description = "처리 결과를 알려야 하는 기한") OffsetDateTime resultNoticeDueAt,
    @Schema(description = "결과 이메일 발송 예약 여부") boolean emailNoticeScheduled) {}
