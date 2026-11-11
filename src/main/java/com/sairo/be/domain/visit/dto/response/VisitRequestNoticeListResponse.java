package com.sairo.be.domain.visit.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record VisitRequestNoticeListResponse(List<Item> items) {

  public record Item(
      Long noticeId,
      Long requestId,
      Long coordinationId,
      String noticeType,
      String channel,
      OffsetDateTime createdAt,
      OffsetDateTime readAt) {}
}
