package com.sairo.be.domain.customer.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record CustomerConsultationRevisionListResponse(List<Item> items) {

  public record Item(
      OffsetDateTime previousOccurredAt,
      String previousContent,
      String previousConsultationType,
      String correctedBy,
      OffsetDateTime correctedAt,
      String correctionReason) {}
}
