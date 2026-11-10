package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "재동의 대기 항목 응답")
public record TermsReconsentResponse(boolean reconsentRequired, List<Item> items) {

  @Schema(description = "재동의 대기 항목")
  public record Item(
      String termsCode,
      String previousVersion,
      String version,
      String category,
      boolean required,
      OffsetDateTime effectiveAt,
      String changeSummary,
      String documentUrl,
      String previousDocumentUrl) {}
}
