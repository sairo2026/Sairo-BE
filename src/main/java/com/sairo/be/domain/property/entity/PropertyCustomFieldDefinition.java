package com.sairo.be.domain.property.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "property_custom_field_definition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyCustomFieldDefinition {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "category_id", nullable = false, updatable = false)
  private Long categoryId;

  @Column(name = "office_id", updatable = false)
  private Long officeId;

  @Column(name = "field_name", nullable = false, length = 50)
  private String fieldName;

  @Enumerated(EnumType.STRING)
  @Column(name = "field_type", nullable = false, length = 10, updatable = false)
  private FieldType fieldType;

  @Enumerated(EnumType.STRING)
  @Column(name = "number_kind", length = 7, updatable = false)
  private NumberKind numberKind;

  @Column(length = 20, updatable = false)
  private String unit;

  @Column(name = "min_value", updatable = false)
  private BigDecimal minValue;

  @Column(name = "max_value", updatable = false)
  private BigDecimal maxValue;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static PropertyCustomFieldDefinition custom(
      Long categoryId, Long officeId, String fieldName, FieldType fieldType) {
    PropertyCustomFieldDefinition definition = new PropertyCustomFieldDefinition();
    definition.categoryId = categoryId;
    definition.officeId = officeId;
    definition.fieldName = fieldName;
    definition.fieldType = fieldType;
    definition.numberKind = fieldType == FieldType.NUMBER ? NumberKind.DECIMAL : null;
    definition.active = true;
    return definition;
  }

  public void rename(String fieldName) {
    this.fieldName = fieldName;
  }

  public void changeActive(boolean active) {
    this.active = active;
  }

  public boolean isSystem() {
    return officeId == null;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
