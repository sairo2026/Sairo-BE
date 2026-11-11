package com.sairo.be.domain.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "시행 중인 정책 문서. 문서 종류마다 시행 중인 최신 버전 하나씩 돌려준다.")
public record PolicyDocumentListResponse(List<Item> items) {

  public record Item(
      String docType,
      String version,
      OffsetDateTime effectiveAt,
      boolean requiresConsent,
      String body) {}
}
