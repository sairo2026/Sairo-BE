package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "시행 중인 약관 목록 응답")
public record TermsCatalogResponse(List<Item> items) {

  @Schema(description = "약관 항목")
  public record Item(
      String termsCode,
      String version,
      boolean required,
      String title,
      String documentUrl,
      String category,
      boolean reconsentRequired,
      String changeSummary) {}
}
