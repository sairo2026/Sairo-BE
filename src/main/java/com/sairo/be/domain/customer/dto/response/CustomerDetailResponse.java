package com.sairo.be.domain.customer.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "고객 상세")
public record CustomerDetailResponse(
    Long customerId,
    String name,
    boolean isCorporation,
    Long managingMembershipId,
    @Schema(nullable = true) String phone,
    @Schema(nullable = true) String secondaryPhone,
    @Schema(nullable = true) String email,
    @Schema(nullable = true) String memo,
    String dataSource,
    String processingBasis,
    String dataPurpose,
    LocalDate retentionUntil,
    @Schema(nullable = true) OffsetDateTime deletedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    String status,
    @Schema(nullable = true) Long mergedIntoCustomerId,
    List<String> roles,
    boolean deletable,
    List<DemandConditionResponse> demandConditions,
    List<RelatedProperty> relatedProperties,
    List<RelatedProperty> currentOwnedOccupied,
    List<ContractItem> upcomingContracts,
    List<ContractItem> pastContracts,
    List<ConsultationItem> consultations,
    List<VisitItem> visits,
    List<TaskItem> tasks,
    Long officeId,
    Long version) {

  public record RelatedProperty(
      Long propertyId,
      String propertyName,
      String role,
      LocalDate startDate,
      @Schema(nullable = true) LocalDate endDate,
      @Schema(nullable = true) Long linkId,
      @Schema(nullable = true) Long interestId,
      @Schema(nullable = true) String note) {}

  public record ContractItem(
      Long contractId,
      Long propertyId,
      String propertyName,
      String dealType,
      String contractStatus,
      @Schema(nullable = true) LocalDate signedDate,
      @Schema(nullable = true) LocalDate leaseStartDate,
      @Schema(nullable = true) LocalDate leaseEndDate) {}

  public record ConsultationItem(
      Long consultationId,
      String consultationType,
      OffsetDateTime occurredAt,
      String content,
      Long authorMembershipId,
      String authorName,
      int revisionCount) {}

  public record VisitItem(
      Long coordinationId,
      String status,
      LocalDate periodStartDate,
      LocalDate periodEndDate,
      int candidatePropertyCount) {}

  public record TaskItem(Long taskId, String title, LocalDate dueDate, String status) {}
}
