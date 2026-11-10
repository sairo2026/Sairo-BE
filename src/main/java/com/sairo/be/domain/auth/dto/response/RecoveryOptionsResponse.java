package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "계정 복구 안내 응답")
public record RecoveryOptionsResponse(
    @Schema(description = "기존 계정 존재 여부") boolean existingAccountFound,
    @Schema(description = "기존 계정이 쓰는 로그인 수단 EMAIL/KAKAO") List<String> availableLoginMethods,
    @Schema(description = "비밀번호 재설정 가능 여부") boolean passwordResetAvailable) {}
