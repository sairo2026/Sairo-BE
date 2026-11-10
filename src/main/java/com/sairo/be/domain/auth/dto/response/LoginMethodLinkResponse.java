package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인수단 연결 응답")
public record LoginMethodLinkResponse(
    @Schema(description = "연결된 로그인수단 EMAIL/KAKAO") String method,
    @Schema(description = "연결 완료 여부") boolean linked) {}
