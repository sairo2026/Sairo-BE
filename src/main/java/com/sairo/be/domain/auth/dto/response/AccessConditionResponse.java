package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "로그인·가입 성공 뒤 접근 상태 판정 결과")
public record AccessConditionResponse(
    @Schema(description = "접근 상태 8조건 중 하나") String accessCondition,
    @Schema(description = "승인된 소속의 사무소 ID") Long officeId,
    @Schema(description = "승인된 소속의 역할 OWNER/AGENT/DEPUTY") String role,
    @Schema(description = "진행 중인 사무소 인증 신청 ID") Long pendingRegistrationId,
    @Schema(description = "진행 중인 사무소 참여 요청 ID") Long pendingMembershipId,
    @Schema(description = "미해결 참여거절·신규반려") UnresolvedRejectionResponse unresolvedRejection,
    @Schema(description = "미납 유예 종료 시각") OffsetDateTime graceUntil,
    @Schema(description = "반환 기간 종료 시각") OffsetDateTime returnPeriodEndsAt,
    @Schema(description = "인증 회수 또는 재인증 중단 구분") String restrictionReason,
    @Schema(description = "퇴사, 내보내짐, 접근 정지 구분") String removalReason) {}
