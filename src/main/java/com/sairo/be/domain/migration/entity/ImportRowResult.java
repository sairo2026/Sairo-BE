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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "import_row_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportRowResult {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "batch_id", nullable = false, updatable = false)
  private Long batchId;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "source_row_number", nullable = false, updatable = false)
  private Integer sourceRowNumber;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private ImportRowOutcome result;

  @Column(name = "failure_reason", columnDefinition = "text")
  private String failureReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static ImportRowResult succeeded(Long batchId, Long officeId, int rowNumber) {
    ImportRowResult row = new ImportRowResult();
    row.batchId = batchId;
    row.officeId = officeId;
    row.sourceRowNumber = rowNumber;
    row.result = ImportRowOutcome.SUCCEEDED;
    return row;
  }

  public static ImportRowResult failed(
      Long batchId, Long officeId, int rowNumber, String failureReason) {
    ImportRowResult row = new ImportRowResult();
    row.batchId = batchId;
    row.officeId = officeId;
    row.sourceRowNumber = rowNumber;
    row.result = ImportRowOutcome.FAILED;
    row.failureReason = failureReason;
    return row;
  }

  public void markSucceeded() {
    this.result = ImportRowOutcome.SUCCEEDED;
    this.failureReason = null;
  }

  public void markFailed(String failureReason) {
    this.result = ImportRowOutcome.FAILED;
    this.failureReason = failureReason;
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
