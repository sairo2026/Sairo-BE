package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "계약 상세(ContractDetailResponse)다. 계약·임대기간·잔금 상태는 서로 독립이다.")
public record ContractDetailResponse(
    Long contractId,
    Long propertyId,
    @Schema(description = "매물 주소. 주소가 비어 있는 매물은 매물명") String propertyAddress,
    @Schema(allowableValues = {"SALE", "JEONSE", "MONTHLY"}) String dealType,
    @Schema(allowableValues = {"DRAFT", "SIGNED", "ENDED", "TERMINATED", "CANCELLED", "VOID"})
        String contractStatus,
    @Schema(
            nullable = true,
            allowableValues = {"BEFORE_START", "IN_PROGRESS", "MATURITY_CONFIRMATION_NEEDED"})
        String leasePeriodStatus,
    @Schema(allowableValues = {"UNDECIDED", "SCHEDULED", "PARTIAL", "COMPLETE", "NONE"})
        String settlementStatus,
    Long managingMembershipId,
    String managingMemberName,
    @Schema(nullable = true) LocalDate signedDate,
    @Schema(nullable = true) Long salePrice,
    @Schema(nullable = true) Long depositAmount,
    @Schema(nullable = true) Long monthlyRentAmount,
    @Schema(nullable = true) LocalDate balanceDueDate,
    @Schema(nullable = true) String balanceDueDatePendingReason,
    @Schema(nullable = true) LocalDate leaseStartDate,
    @Schema(nullable = true) LocalDate leaseEndDate,
    @Schema(nullable = true) Long previousContractId,
    @Schema(nullable = true) Long currentRevisionId,
    @Schema(nullable = true) String memo,
    boolean duplicateExcluded,
    @Schema(nullable = true) Long duplicateOfContractId,
    @Schema(nullable = true) OffsetDateTime duplicateExcludedAt,
    @Schema(nullable = true) String duplicateExclusionReason,
    List<PartyResponse> sellerLandlordParties,
    List<PartyResponse> buyerTenantParties,
    List<Document> documents,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    long version) {

  @Schema(description = "계약에 첨부된 서류 한 버전이다.")
  public record Document(
      Long documentId,
      @Schema(allowableValues = {"SALES_CONTRACT", "DISCLOSURE_STATEMENT"}) String documentType,
      int versionNo,
      @Schema(nullable = true) Long contractRevisionId,
      @Schema(nullable = true) LocalDate writtenDate,
      @Schema(nullable = true) LocalDate deliveredDate,
      @Schema(nullable = true) String datePendingReason,
      int retentionYears,
      @Schema(nullable = true) LocalDate retentionEndDate,
      boolean piiMaskingConfirmed,
      @Schema(nullable = true) Long supersedesDocumentId) {}
}
