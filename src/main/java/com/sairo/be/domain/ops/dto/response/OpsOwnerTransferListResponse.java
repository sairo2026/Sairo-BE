package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "운영자가 보는 대표 이전 목록")
public record OpsOwnerTransferListResponse(
    List<Item> items, int page, int size, long totalElements) {

  public record Item(
      Long transferId,
      Long officeId,
      String officeName,
      String fromDisplayName,
      String toDisplayName,
      String status,
      OffsetDateTime requestedAt,
      OffsetDateTime acceptDeadlineAt,
      OffsetDateTime acceptedAt,
      OffsetDateTime completedAt) {}
}
