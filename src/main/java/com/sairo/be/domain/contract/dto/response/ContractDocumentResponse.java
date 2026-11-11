package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Schema(
    description =
        "계약 서류 업로드·정정본 업로드 결과(ContractDocumentResponse, ContractDocumentRevisionResponse)다."
            + " version은 부모 계약의 현재 버전이다.")
public record ContractDocumentResponse(
    Long documentId,
    @Schema(allowableValues = {"SALES_CONTRACT", "DISCLOSURE_STATEMENT"}) String documentType,
    int versionNo,
    Long contractRevisionId,
    @Schema(nullable = true) LocalDate writtenDate,
    @Schema(nullable = true) LocalDate deliveredDate,
    @Schema(nullable = true) String datePendingReason,
    int retentionYears,
    @Schema(nullable = true) LocalDate retentionEndDate,
    boolean piiMaskingConfirmed,
    @Schema(nullable = true) Long supersedesDocumentId,
    @Schema(nullable = true, description = "마스킹 사본이면 법정 원본 보존 완료로 보지 않는다는 안내") String notice,
    Long officeId,
    long version) {

  @Schema(description = "계약 서류 다운로드 링크(ContractDocumentDownloadResponse)다.")
  public record DocumentDownload(
      Long documentId, String downloadUrl, OffsetDateTime expiresAt, Long officeId, long version) {}
}
