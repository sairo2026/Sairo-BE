package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "업로드 파일 안의 시트 한 건")
public record ImportSheetResponse(String name, int rowCount, int columnCount) {}
