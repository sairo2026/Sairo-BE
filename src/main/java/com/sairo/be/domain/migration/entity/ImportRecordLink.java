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
@Table(name = "import_record_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportRecordLink {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "batch_id", nullable = false, updatable = false)
  private Long batchId;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "source_system", nullable = false, updatable = false, length = 100)
  private String sourceSystem;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, updatable = false, length = 10)
  private ImportTargetType sourceType;

  @Column(name = "source_id", nullable = false, updatable = false, length = 200)
  private String sourceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_entity_type", nullable = false, updatable = false, length = 10)
  private ImportTargetType targetEntityType;

  @Column(name = "target_entity_id", nullable = false, updatable = false)
  private Long targetEntityId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static ImportRecordLink of(
      Long batchId,
      Long officeId,
      String sourceSystem,
      ImportTargetType type,
      String sourceId,
      Long targetEntityId) {
    ImportRecordLink link = new ImportRecordLink();
    link.batchId = batchId;
    link.officeId = officeId;
    link.sourceSystem = sourceSystem;
    link.sourceType = type;
    link.sourceId = sourceId;
    link.targetEntityType = type;
    link.targetEntityId = targetEntityId;
    return link;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
