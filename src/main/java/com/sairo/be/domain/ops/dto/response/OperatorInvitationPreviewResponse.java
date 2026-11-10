package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "운영자 초대 확인 응답")
public record OperatorInvitationPreviewResponse(
    String inviteeName, List<String> roles, OffsetDateTime expiresAt) {}
