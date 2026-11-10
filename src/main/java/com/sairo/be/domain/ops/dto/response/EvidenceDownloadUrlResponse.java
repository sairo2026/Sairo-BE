package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "증빙서류 다운로드 URL")
public record EvidenceDownloadUrlResponse(String downloadUrl, OffsetDateTime expiresAt) {}
