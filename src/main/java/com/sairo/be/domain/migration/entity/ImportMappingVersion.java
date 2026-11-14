package com.sairo.be.domain.migration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "import_mapping_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportMappingVersion {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "import_batch_id", nullable = false, updatable = false)
  private Long importBatchId;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "version_no", nullable = false, updatable = false)
  private Integer versionNo;

  @Column(name = "source_system", nullable = false, updatable = false, length = 100)
  private String sourceSystem;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false, updatable = false, length = 10)
  private ImportTargetType targetType;

  @Enumerated(EnumType.STRING)
  @Column(name = "confirmed_by_type", nullable = false, updatable = false, length = 10)
  private MappingConfirmerType confirmedByType;

  @Column(name = "confirmed_by_membership_id", updatable = false)
  private Long confirmedByMembershipId;

  @Column(name = "confirmed_by_operator_id", updatable = false)
  private Long confirmedByOperatorId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static ImportMappingVersion byMembership(
      ImportBatch batch, int versionNo, String sourceSystem, Long membershipId) {
    ImportMappingVersion version = of(batch, versionNo, sourceSystem);
    version.confirmedByType = MappingConfirmerType.MEMBERSHIP;
    version.confirmedByMembershipId = membershipId;
    return version;
  }

  public static ImportMappingVersion byOperator(
      ImportBatch batch, int versionNo, String sourceSystem, Long operatorId) {
    ImportMappingVersion version = of(batch, versionNo, sourceSystem);
    version.confirmedByType = MappingConfirmerType.OPERATOR;
    version.confirmedByOperatorId = operatorId;
    return version;
  }

  private static ImportMappingVersion of(ImportBatch batch, int versionNo, String sourceSystem) {
    ImportMappingVersion version = new ImportMappingVersion();
    version.importBatchId = batch.getId();
    version.officeId = batch.getOfficeId();
    version.versionNo = versionNo;
    version.sourceSystem = sourceSystem;
    version.targetType = batch.getTargetType();
    return version;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
