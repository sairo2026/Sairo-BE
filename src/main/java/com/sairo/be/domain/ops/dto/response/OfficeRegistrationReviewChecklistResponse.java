package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "인증 심사 필수 확인 항목 상태")
public record OfficeRegistrationReviewChecklistResponse(
    Long registrationId,
    int revisionNo,
    List<ReviewChecklistItemResponse> reviewChecklist,
    boolean canApprove) {}
