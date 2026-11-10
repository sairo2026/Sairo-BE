package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "내보내기 표 목록. 대표에게는 7개 표 전체와 현재 허용 여부(allowed)를, 소속공인중개사에게는 허용된 표만 돌려준다.")
public record ExportAllowedTablesResponse(List<Table> tables) {

  public record Table(
      String code,
      String label,
      @Schema(description = "현재 허용 여부. 문서에 없는 선택 필드다.") boolean allowed) {}
}
