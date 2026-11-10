package com.sairo.be.domain.office.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사무소 등록 신청 취소 요청")
public record OfficeRegistrationCancelRequest(
    @Schema(description = "취소 사유(선택)") @PlainText(min = 0, max = 500) String reason) {}
