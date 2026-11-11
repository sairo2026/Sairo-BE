package com.sairo.be.domain.home.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "알림함 목록(NotificationListResponse)의 항목이다.")
public record NotificationItem(
    Long notificationId,
    String type,
    String message,
    boolean read,
    String targetType,
    @Schema(nullable = true) Long targetId,
    OffsetDateTime createdAt) {}
