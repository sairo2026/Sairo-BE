package com.sairo.be.domain.migration.entity;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "import_support_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportSupportRequest {

  public static final int CHANGE_REQUEST_LIMIT = 1;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "import_batch_id", nullable = false, updatable = false)
  private Long importBatchId;

  @Column(name = "source_system", nullable = false, length = 100)
  private String sourceSystem;

  @Column(name = "estimated_row_count")
  private Integer estimatedRowCount;

  @Column(name = "quote_amount")
  private Long quoteAmount;

  @Column(name = "quote_scope_note", columnDefinition = "text")
  private String quoteScopeNote;

  @Column(name = "payment_order_id", length = 64)
  private String paymentOrderId;

  @Column(name = "payment_amount")
  private Long paymentAmount;

  @Column(name = "pg_transaction_id", length = 200)
  private String pgTransactionId;

  @Column(name = "paid_at")
  private Instant paidAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private ImportSupportStatus status;

  @Column(name = "requested_at", nullable = false, updatable = false)
  private Instant requestedAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  @Column(name = "assigned_operator_id")
  private Long assignedOperatorId;

  @Column(name = "processing_started_at")
  private Instant processingStartedAt;

  @Column(name = "submitted_mapping_version_id")
  private Long submittedMappingVersionId;

  @Column(name = "submitted_at")
  private Instant submittedAt;

  @Column(name = "change_request_count", nullable = false)
  private int changeRequestCount;

  @Column(name = "change_requested_at")
  private Instant changeRequestedAt;

  @Column(name = "change_request_reason", columnDefinition = "text")
  private String changeRequestReason;

  @Column(name = "approved_mapping_version_id")
  private Long approvedMappingVersionId;

  @Column(name = "approved_by_membership_id")
  private Long approvedByMembershipId;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "cancelled_by_membership_id")
  private Long cancelledByMembershipId;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Column(name = "cancel_reason", columnDefinition = "text")
  private String cancelReason;

  @Column(name = "rejected_by_operator_id")
  private Long rejectedByOperatorId;

  @Column(name = "rejected_at")
  private Instant rejectedAt;

  @Column(name = "reject_reason", columnDefinition = "text")
  private String rejectReason;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static ImportSupportRequest request(
      Long officeId, Long importBatchId, String sourceSystem, int estimatedRowCount, Instant now) {
    ImportSupportRequest request = new ImportSupportRequest();
    request.officeId = officeId;
    request.importBatchId = importBatchId;
    request.sourceSystem = sourceSystem;
    request.estimatedRowCount = estimatedRowCount;
    request.status = ImportSupportStatus.REQUESTED;
    request.requestedAt = now;
    request.updatedAt = now;
    return request;
  }

  public void quote(long amount, String scopeNote) {
    requireStatus(ImportSupportStatus.REQUESTED, ImportSupportStatus.QUOTED);
    this.quoteAmount = amount;
    this.quoteScopeNote = scopeNote;
    this.status = ImportSupportStatus.QUOTED;
  }

  public void acceptQuote(String orderId) {
    requireStatus(ImportSupportStatus.QUOTED);
    this.paymentOrderId = orderId;
    this.paymentAmount = quoteAmount;
    this.status = ImportSupportStatus.ACCEPTED;
  }

  public void markPaid(String pgTransactionId, Instant now) {
    requireStatus(ImportSupportStatus.ACCEPTED);
    this.pgTransactionId = pgTransactionId;
    this.paidAt = now;
    this.status = ImportSupportStatus.PAID;
  }

  public void startProcessing(Long operatorId, Instant now) {
    requireStatus(ImportSupportStatus.PAID);
    this.assignedOperatorId = operatorId;
    this.processingStartedAt = now;
    this.status = ImportSupportStatus.PROCESSING;
  }

  public void submitForReview(Long mappingVersionId, Instant now) {
    requireStatus(ImportSupportStatus.PROCESSING);
    this.submittedMappingVersionId = mappingVersionId;
    this.submittedAt = now;
    this.status = ImportSupportStatus.REVIEW;
  }

  public void approve(Long membershipId, Instant now) {
    requireStatus(ImportSupportStatus.REVIEW);
    this.approvedMappingVersionId = submittedMappingVersionId;
    this.approvedByMembershipId = membershipId;
    this.approvedAt = now;
    this.status = ImportSupportStatus.APPROVED;
  }

  public void requestChanges(String reason, Instant now) {
    requireStatus(ImportSupportStatus.REVIEW);
    if (changeRequestCount >= CHANGE_REQUEST_LIMIT) {
      throw new BusinessException(ErrorCode.CHANGE_REQUEST_LIMIT_EXCEEDED);
    }
    this.changeRequestCount = changeRequestCount + 1;
    this.changeRequestReason = reason;
    this.changeRequestedAt = now;
    this.status = ImportSupportStatus.PROCESSING;
  }

  public void cancel(Long membershipId, String reason, Instant now) {
    if (!status.isCancellableByOwner()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    this.cancelledByMembershipId = membershipId;
    this.cancelReason = reason;
    this.cancelledAt = now;
    this.status = ImportSupportStatus.CANCELLED;
  }

  public void reject(Long operatorId, String reason, Instant now) {
    requireStatus(ImportSupportStatus.REQUESTED, ImportSupportStatus.QUOTED);
    this.rejectedByOperatorId = operatorId;
    this.rejectReason = reason;
    this.rejectedAt = now;
    this.status = ImportSupportStatus.REJECTED;
  }

  public void deliver(Instant now) {
    requireStatus(ImportSupportStatus.APPROVED);
    this.deliveredAt = now;
    this.status = ImportSupportStatus.DELIVERED;
  }

  public boolean isChangeRequestAvailable() {
    return changeRequestCount < CHANGE_REQUEST_LIMIT;
  }

  private void requireStatus(ImportSupportStatus... allowed) {
    for (ImportSupportStatus candidate : allowed) {
      if (status == candidate) {
        return;
      }
    }
    throw new BusinessException(ErrorCode.INVALID_TRANSITION);
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (requestedAt == null) {
      requestedAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
