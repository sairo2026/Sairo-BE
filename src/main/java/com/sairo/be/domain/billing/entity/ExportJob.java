package com.sairo.be.domain.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "export_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExportJob {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "created_by_membership_id", nullable = false, updatable = false)
  private Long createdByMembershipId;

  @Column(nullable = false, length = 10, updatable = false)
  private String scope;

  @Column(nullable = false, length = 10)
  private String status;

  @Column(name = "file_object_key", length = 500)
  private String fileObjectKey;

  /** 생성 실패 사유다. 생성 작업(migration ExportJobJdbcRepository)이 기록하고 여기서는 읽기만 한다. */
  @Column(name = "failure_reason", columnDefinition = "text", insertable = false, updatable = false)
  private String failureReason;

  @Column(name = "file_expires_at", nullable = false, updatable = false)
  private Instant fileExpiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static ExportJob fullExport(
      Long officeId, Long membershipId, Instant now, Instant fileExpiresAt) {
    ExportJob job = new ExportJob();
    job.officeId = officeId;
    job.createdByMembershipId = membershipId;
    job.scope = "FULL";
    job.status = "PENDING";
    job.fileExpiresAt = fileExpiresAt;
    job.createdAt = now;
    job.updatedAt = now;
    return job;
  }

  public boolean isCompleted() {
    return "COMPLETED".equals(status);
  }
}
