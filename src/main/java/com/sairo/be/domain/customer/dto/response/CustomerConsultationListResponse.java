package com.sairo.be.domain.customer.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record CustomerConsultationListResponse(List<Item> items) {

  public record Item(
      Long consultationId,
      String consultationType,
      OffsetDateTime occurredAt,
      String content,
      Long authorMembershipId,
      String authorName,
      int revisionCount,
      Long officeId,
      Long version) {}
}
