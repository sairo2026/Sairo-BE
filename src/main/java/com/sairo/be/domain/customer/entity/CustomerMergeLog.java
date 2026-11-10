package com.sairo.be.domain.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer_merge_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerMergeLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "primary_customer_id", nullable = false, updatable = false)
  private Long primaryCustomerId;

  @Column(name = "merged_customer_id", nullable = false, updatable = false)
  private Long mergedCustomerId;

  @Column(name = "executed_by_membership_id", nullable = false, updatable = false)
  private Long executedByMembershipId;

  @Column(name = "executed_at", nullable = false, updatable = false)
  private Instant executedAt;

  @Column(name = "conflict_resolution_note", columnDefinition = "text", updatable = false)
  private String conflictResolutionNote;

  public static CustomerMergeLog record(
      Long primaryCustomerId, Long mergedCustomerId, Long executedByMembershipId, String note) {
    CustomerMergeLog log = new CustomerMergeLog();
    log.primaryCustomerId = primaryCustomerId;
    log.mergedCustomerId = mergedCustomerId;
    log.executedByMembershipId = executedByMembershipId;
    log.conflictResolutionNote = note;
    return log;
  }

  @PrePersist
  void onCreate() {
    executedAt = Instant.now();
  }
}
