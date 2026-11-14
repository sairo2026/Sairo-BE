package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "필수 확인 항목의 확인 상태")
public record ReviewChecklistItemResponse(
    String itemCode, boolean checked, String checkedByOperatorName, OffsetDateTime checkedAt) {}
