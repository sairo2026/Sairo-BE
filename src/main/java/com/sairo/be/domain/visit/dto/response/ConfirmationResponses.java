package com.sairo.be.domain.visit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public final class ConfirmationResponses {

  private ConfirmationResponses() {}

  public record Messages(
      long scheduleVersion, String customerMessage, List<ApproverMessage> approverMessages) {}

  public record ApproverMessage(
      Long candidatePropertyId, Long approvalId, String approverName, String message) {}

  public record DeliveryList(List<DeliveryItem> items) {}

  public record DeliveryItem(
      Long deliveryId,
      long scheduleVersion,
      String recipientType,
      @Schema(nullable = true) Long candidatePropertyId,
      boolean delivered,
      @Schema(nullable = true) String channel,
      @Schema(nullable = true) OffsetDateTime deliveredAt,
      OffsetDateTime createdAt) {}

  public record DeliveryCreated(
      Long deliveryId,
      Long coordinationId,
      long scheduleVersion,
      String recipientType,
      @Schema(nullable = true) Long candidatePropertyId,
      boolean delivered,
      @Schema(nullable = true) String channel,
      @Schema(nullable = true) OffsetDateTime deliveredAt,
      OffsetDateTime createdAt,
      Long officeId,
      long version) {}
}
