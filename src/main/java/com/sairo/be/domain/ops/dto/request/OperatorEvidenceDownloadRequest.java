package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.domain.office.entity.EvidenceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "증빙서류 다운로드 URL 요청")
public record OperatorEvidenceDownloadRequest(
    @Schema(description = "내려받을 증빙 종류") @NotNull EvidenceType evidenceType) {}
