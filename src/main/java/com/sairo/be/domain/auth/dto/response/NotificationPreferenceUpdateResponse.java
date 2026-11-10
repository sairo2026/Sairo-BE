package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "알림 수신 설정 응답")
public record NotificationPreferenceUpdateResponse(
    @Schema(description = "알림 유형별 수신 설정 맵") Map<String, Boolean> channels) {}
