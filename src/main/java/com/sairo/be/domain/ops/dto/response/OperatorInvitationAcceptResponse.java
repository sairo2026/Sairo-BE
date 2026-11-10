package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "운영자 초대 수락 응답")
public record OperatorInvitationAcceptResponse(
    Long operatorId, String name, List<String> roles, OffsetDateTime acceptedAt) {}
