package com.sairo.be.domain.visit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public final class ConfirmationResponses {

  private ConfirmationResponses() {}

  public record Messages(
      long scheduleVersion,
      @Schema(description = "안내문을 만들 수 있으면 true. 모든 매물이 확정되기 전이면 false이고 안내문은 비어 있다")
          boolean available,
      @Schema(
              nullable = true,
              description = "안내문이 없는 이유. 모든 매물이 확정되기 전이면 NOT_ALL_CONFIRMED이고 안내문이 있으면 null이다")
          String unavailableReason,
      @Schema(nullable = true, description = "고객 안내문. available이 false이면 null이다")
          String customerMessage,
      List<ApproverMessage> approverMessages) {

    public static final String NOT_ALL_CONFIRMED = "NOT_ALL_CONFIRMED";

    public static Messages ready(
        long scheduleVersion, String customerMessage, List<ApproverMessage> approverMessages) {
      return new Messages(scheduleVersion, true, null, customerMessage, approverMessages);
    }

    public static Messages notAllConfirmed(long scheduleVersion) {
      return new Messages(scheduleVersion, false, NOT_ALL_CONFIRMED, null, List.of());
    }
  }

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
