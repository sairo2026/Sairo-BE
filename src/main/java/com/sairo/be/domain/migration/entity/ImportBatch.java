package com.sairo.be.domain.migration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "import_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportBatch {

  public static final String UNSPECIFIED_SOURCE_SYSTEM = "UNSPECIFIED";
  public static final Duration CANCEL_WINDOW = Duration.ofHours(24);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "uploaded_by_membership_id", nullable = false, updatable = false)
  private Long uploadedByMembershipId;

  @Column(name = "source_system", nullable = false, length = 100)
  private String sourceSystem;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false, updatable = false, length = 10)
  private ImportTargetType targetType;

  @Column(name = "file_object_key", nullable = false, updatable = false, length = 500)
  private String fileObjectKey;

  @Column(name = "file_name", nullable = false, updatable = false, length = 255)
  private String fileName;

  @Column(name = "selected_sheet_name", length = 100)
  private String selectedSheetName;

  @Column(name = "row_count")
  private Integer rowCount;

  @Column(name = "succeeded_count")
  private Integer succeededCount;

  @Column(name = "failed_count")
  private Integer failedCount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private ImportBatchStatus status;

  @Column(name = "executed_at")
  private Instant executedAt;

  @Column(name = "cancellable_until")
  private Instant cancellableUntil;

  @Column(name = "cancelled_at", insertable = false, updatable = false)
  private Instant cancelledAt;

  @Column(name = "cancelled_count", insertable = false, updatable = false)
  private Integer cancelledCount;

  @Column(name = "failure_reason", columnDefinition = "text")
  private String failureReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static ImportBatch upload(
      Long officeId,
      Long uploadedByMembershipId,
      ImportTargetType targetType,
      String fileObjectKey,
      String fileName,
      String selectedSheetName) {
    ImportBatch batch = new ImportBatch();
    batch.officeId = officeId;
    batch.uploadedByMembershipId = uploadedByMembershipId;
    batch.sourceSystem = UNSPECIFIED_SOURCE_SYSTEM;
    batch.targetType = targetType;
    batch.fileObjectKey = fileObjectKey;
    batch.fileName = fileName;
    batch.selectedSheetName = selectedSheetName;
    batch.status = ImportBatchStatus.UPLOADED;
    return batch;
  }

  public void selectSheet(String sheetName) {
    this.selectedSheetName = sheetName;
    this.rowCount = null;
    this.status = ImportBatchStatus.UPLOADED;
  }

  public void confirmMapping(String sourceSystem, int rowCount) {
    this.sourceSystem = sourceSystem;
    this.rowCount = rowCount;
    this.status = ImportBatchStatus.MAPPING;
  }

  public void startExecution() {
    this.status = ImportBatchStatus.EXECUTING;
    this.cancellableUntil = null;
    this.failureReason = null;
  }

  public void finishExecution(int succeeded, int failed, Instant now, boolean createdAny) {
    this.succeededCount = succeeded;
    this.failedCount = failed;
    this.executedAt = now;
    boolean allFailed = succeeded == 0 && failed > 0;
    this.status = allFailed ? ImportBatchStatus.FAILED : ImportBatchStatus.COMPLETED;
    this.cancellableUntil = createdAny ? now.plus(CANCEL_WINDOW) : null;
    this.failureReason = failed > 0 ? failed + "건이 처리에 실패했습니다." : null;
  }

  public void failExecution(int succeeded, int failed, Instant now, boolean createdAny) {
    this.succeededCount = succeeded;
    this.failedCount = failed;
    this.executedAt = now;
    this.status = ImportBatchStatus.FAILED;
    this.cancellableUntil = createdAny ? now.plus(CANCEL_WINDOW) : null;
    this.failureReason = "실행 중 오류가 발생해 일부 줄을 처리하지 못했습니다.";
  }

  public boolean isCancelWindowOpen(Instant now) {
    return cancellableUntil != null && !now.isAfter(cancellableUntil);
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
