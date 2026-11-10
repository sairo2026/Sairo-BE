package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "이전 파일 업로드·시트 변경 직후의 실행 단위 상태")
public record ImportBatchStatusResponse(
    Long batchId,
    String status,
    String fileName,
    List<ImportSheetResponse> sheets,
    String selectedSheetName,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
