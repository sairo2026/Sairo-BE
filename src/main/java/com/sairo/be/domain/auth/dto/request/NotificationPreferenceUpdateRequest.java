package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

@Schema(description = "알림 수신 채널 설정 요청")
public record NotificationPreferenceUpdateRequest(
    @Schema(description = "알림 유형별 수신 on/off. 키는 VISIT_REMINDER, SECURITY, MARKETING") @NotEmpty Map<String, Boolean> channels) {}
