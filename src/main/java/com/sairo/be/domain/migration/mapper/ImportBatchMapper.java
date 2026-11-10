package com.sairo.be.domain.migration.mapper;

import com.sairo.be.domain.migration.dto.response.ImportBatchDetailResponse;
import com.sairo.be.domain.migration.dto.response.ImportBatchListResponse;
import com.sairo.be.domain.migration.dto.response.ImportBatchStatusResponse;
import com.sairo.be.domain.migration.dto.response.ImportCancelResponse;
import com.sairo.be.domain.migration.dto.response.ImportExecuteResponse;
import com.sairo.be.domain.migration.dto.response.ImportMappingConfirmResponse;
import com.sairo.be.domain.migration.dto.response.ImportSheetResponse;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportBatchStatus;
import com.sairo.be.domain.migration.entity.ImportNotCancelledItem;
import com.sairo.be.domain.migration.entity.ImportSheetInfo;
import com.sairo.be.domain.migration.repository.ImportSupportLinkRepository.ActiveRequest;
import com.sairo.be.global.support.TimeRules;
import java.util.List;

public final class ImportBatchMapper {

  private ImportBatchMapper() {}

  public static List<ImportSheetResponse> toSheets(List<ImportSheetInfo> sheets) {
    return sheets.stream()
        .map(sheet -> new ImportSheetResponse(sheet.name(), sheet.rowCount(), sheet.columnCount()))
        .toList();
  }

  public static ImportBatchStatusResponse toStatus(
      ImportBatch batch, List<ImportSheetInfo> sheets) {
    return new ImportBatchStatusResponse(
        batch.getId(),
        batch.getStatus().name(),
        batch.getFileName(),
        toSheets(sheets),
        batch.getSelectedSheetName(),
        batch.getOfficeId(),
        TimeRules.toDisplayOffset(batch.getCreatedAt()),
        TimeRules.toDisplayOffset(batch.getUpdatedAt()),
        batch.getVersion());
  }

  public static ImportMappingConfirmResponse toMappingConfirm(ImportBatch batch) {
    return new ImportMappingConfirmResponse(
        batch.getId(),
        batch.getOfficeId(),
        batch.getStatus().name(),
        batch.getRowCount(),
        TimeRules.toDisplayOffset(batch.getExecutedAt()),
        TimeRules.toDisplayOffset(batch.getCancellableUntil()),
        TimeRules.toDisplayOffset(batch.getCancelledAt()),
        TimeRules.toDisplayOffset(batch.getCreatedAt()),
        TimeRules.toDisplayOffset(batch.getUpdatedAt()),
        batch.getVersion());
  }

  public static ImportExecuteResponse toExecute(ImportBatch batch) {
    return new ImportExecuteResponse(
        batch.getId(),
        batch.getStatus().name(),
        TimeRules.toDisplayOffset(batch.getCancellableUntil()),
        batch.getOfficeId(),
        TimeRules.toDisplayOffset(batch.getCreatedAt()),
        TimeRules.toDisplayOffset(batch.getUpdatedAt()),
        batch.getVersion());
  }

  public static List<ImportCancelResponse.NotCancelled> toNotCancelled(
      List<ImportNotCancelledItem> items) {
    return items.stream()
        .map(
            item ->
                new ImportCancelResponse.NotCancelled(
                    item.entityType(), item.entityId(), item.reason()))
        .toList();
  }

  public static ImportCancelResponse toCancel(
      ImportBatch batch, int cancelledCount, List<ImportNotCancelledItem> items) {
    return new ImportCancelResponse(
        batch.getId(),
        batch.getOfficeId(),
        batch.getStatus().name(),
        cancelledCount,
        toNotCancelled(items),
        batch.getRowCount(),
        TimeRules.toDisplayOffset(batch.getExecutedAt()),
        TimeRules.toDisplayOffset(batch.getCancellableUntil()),
        TimeRules.toDisplayOffset(batch.getCancelledAt()),
        TimeRules.toDisplayOffset(batch.getCreatedAt()),
        TimeRules.toDisplayOffset(batch.getUpdatedAt()),
        batch.getVersion());
  }

  public static ImportBatchDetailResponse toDetail(
      ImportBatch batch,
      List<ImportSheetInfo> sheets,
      ImportBatchDetailResponse.Counts counts,
      ImportBatchDetailResponse.CancelSummary cancelSummary) {
    return new ImportBatchDetailResponse(
        batch.getId(),
        batch.getFileName(),
        batch.getTargetType().name(),
        batch.getStatus().name(),
        batch.getRowCount(),
        counts,
        TimeRules.toDisplayOffset(batch.getExecutedAt()),
        TimeRules.toDisplayOffset(batch.getCancellableUntil()),
        TimeRules.toDisplayOffset(batch.getCancelledAt()),
        batch.getFailureReason(),
        cancelSummary,
        toSheets(sheets),
        batch.getSelectedSheetName(),
        batch.getOfficeId(),
        TimeRules.toDisplayOffset(batch.getCreatedAt()),
        TimeRules.toDisplayOffset(batch.getUpdatedAt()),
        batch.getVersion());
  }

  public static ImportBatchListResponse.Item toListItem(ImportBatch batch, ActiveRequest support) {
    boolean eligible = batch.getStatus().isEditable() && support == null;
    return new ImportBatchListResponse.Item(
        batch.getId(),
        batch.getFileName(),
        batch.getTargetType().name(),
        batch.getStatus().name(),
        batch.getRowCount(),
        TimeRules.toDisplayOffset(batch.getCreatedAt()),
        TimeRules.toDisplayOffset(batch.getExecutedAt()),
        TimeRules.toDisplayOffset(batch.getCancellableUntil()),
        support == null ? null : support.requestId(),
        support == null ? null : support.status(),
        eligible,
        batch.getOfficeId(),
        TimeRules.toDisplayOffset(batch.getUpdatedAt()),
        batch.getVersion());
  }

  public static boolean isFinished(ImportBatchStatus status) {
    return status == ImportBatchStatus.COMPLETED
        || status == ImportBatchStatus.FAILED
        || status == ImportBatchStatus.CANCELLED;
  }
}
