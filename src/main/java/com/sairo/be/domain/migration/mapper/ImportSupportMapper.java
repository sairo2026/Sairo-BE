package com.sairo.be.domain.migration.mapper;

import com.sairo.be.domain.migration.dto.response.ImportSupportApproveResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportCancelResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportChangeResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportListItem;
import com.sairo.be.domain.migration.dto.response.ImportSupportPaymentConfirmResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportQuoteAcceptResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportStatusResponse;
import com.sairo.be.domain.migration.entity.ImportSupportRequest;
import com.sairo.be.global.support.TimeRules;

public final class ImportSupportMapper {

  private ImportSupportMapper() {}

  public static ImportSupportStatusResponse toStatus(ImportSupportRequest request) {
    return new ImportSupportStatusResponse(
        request.getId(),
        request.getImportBatchId(),
        request.getStatus().name(),
        request.getQuoteAmount(),
        TimeRules.toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }

  public static ImportSupportQuoteAcceptResponse toQuoteAccept(ImportSupportRequest request) {
    return new ImportSupportQuoteAcceptResponse(
        request.getId(),
        request.getOfficeId(),
        request.getSourceSystem(),
        request.getEstimatedRowCount(),
        request.getQuoteAmount(),
        request.getPaymentOrderId(),
        request.getPaymentAmount(),
        request.getStatus().name(),
        TimeRules.toDisplayOffset(request.getRequestedAt()),
        TimeRules.toDisplayOffset(request.getDeliveredAt()));
  }

  public static ImportSupportPaymentConfirmResponse toPaymentConfirm(ImportSupportRequest request) {
    return new ImportSupportPaymentConfirmResponse(
        request.getId(),
        request.getStatus().name(),
        TimeRules.toDisplayOffset(request.getPaidAt()),
        TimeRules.toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }

  public static ImportSupportApproveResponse toApprove(
      ImportSupportRequest request, int approvedMappingVersionNo) {
    return new ImportSupportApproveResponse(
        request.getId(),
        request.getStatus().name(),
        approvedMappingVersionNo,
        TimeRules.toDisplayOffset(request.getApprovedAt()),
        TimeRules.toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }

  public static ImportSupportChangeResponse toChange(ImportSupportRequest request) {
    return new ImportSupportChangeResponse(
        request.getId(),
        request.getStatus().name(),
        request.getChangeRequestCount(),
        TimeRules.toDisplayOffset(request.getChangeRequestedAt()),
        TimeRules.toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }

  public static ImportSupportCancelResponse toCancel(ImportSupportRequest request) {
    return new ImportSupportCancelResponse(
        request.getId(),
        request.getStatus().name(),
        TimeRules.toDisplayOffset(request.getCancelledAt()),
        request.getImportBatchId(),
        !request.getStatus().isActive(),
        TimeRules.toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }

  public static ImportSupportListItem toListItem(ImportSupportRequest request) {
    return new ImportSupportListItem(
        request.getId(),
        request.getImportBatchId(),
        request.getSourceSystem(),
        request.getStatus().name(),
        request.getQuoteAmount(),
        TimeRules.toDisplayOffset(request.getUpdatedAt()));
  }
}
