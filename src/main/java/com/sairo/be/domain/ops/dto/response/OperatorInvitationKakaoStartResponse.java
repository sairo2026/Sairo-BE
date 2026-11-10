package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카카오 인가 URL")
public record OperatorInvitationKakaoStartResponse(String authorizationUrl) {}
