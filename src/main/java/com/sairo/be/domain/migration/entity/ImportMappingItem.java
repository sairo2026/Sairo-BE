package com.sairo.be.domain.migration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "import_mapping_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportMappingItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "mapping_version_id", nullable = false, updatable = false)
  private Long mappingVersionId;

  @Column(name = "source_column", nullable = false, updatable = false, length = 200)
  private String sourceColumn;

  @Column(name = "target_field", nullable = false, updatable = false, length = 100)
  private String targetField;

  @Column(precision = 4, scale = 3, updatable = false)
  private BigDecimal confidence;

  public static ImportMappingItem of(
      Long mappingVersionId, String sourceColumn, String targetField, BigDecimal confidence) {
    ImportMappingItem item = new ImportMappingItem();
    item.mappingVersionId = mappingVersionId;
    item.sourceColumn = sourceColumn;
    item.targetField = targetField;
    item.confidence = confidence;
    return item;
  }
}
