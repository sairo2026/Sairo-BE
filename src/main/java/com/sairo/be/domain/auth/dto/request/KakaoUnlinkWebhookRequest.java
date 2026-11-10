package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카카오 연결 끊김 수신 요청. 폼 본문, 쿼리 문자열, JSON 본문을 모두 읽는다")
public record KakaoUnlinkWebhookRequest(
    @Schema(description = "서비스 앱 ID(app_id)") String appId,
    @Schema(description = "카카오 회원번호(user_id)") String userId,
    @Schema(description = "끊김 경로(referrer_type)") String referrerType,
    @Schema(description = "그룹 사용자 토큰. 쓰지 않고 저장하지 않는다") String groupUserToken) {}
