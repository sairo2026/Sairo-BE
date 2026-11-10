package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "비밀번호 재설정 링크 발송 응답")
public record PasswordResetLinkResponse(@Schema(description = "복구 요청 접수 여부") boolean accepted) {}
