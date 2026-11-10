package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증 완료 사무소 회수 결과")
public record OpsOfficeRevokeResponse(
    Long officeId, Long registrationId, String registrationStatus) {}
