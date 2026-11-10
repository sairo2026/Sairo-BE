package com.sairo.be.domain.property.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "property_field_value")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyFieldValue {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "property_id", nullable = false, updatable = false)
  private Long propertyId;

  @Column(name = "field_definition_id", nullable = false, updatable = false)
  private Long fieldDefinitionId;

  @Column(name = "value_text", columnDefinition = "text")
  private String valueText;

  @Column(name = "value_number")
  private BigDecimal valueNumber;

  @Column(name = "value_boolean")
  private Boolean valueBoolean;

  @Column(name = "value_date")
  private LocalDate valueDate;

  @Column(name = "is_archived", nullable = false)
  private boolean archived;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static PropertyFieldValue of(Long propertyId, Long fieldDefinitionId) {
    PropertyFieldValue value = new PropertyFieldValue();
    value.propertyId = propertyId;
    value.fieldDefinitionId = fieldDefinitionId;
    return value;
  }

  public void assignText(String text) {
    clear();
    this.valueText = text;
  }

  public void assignNumber(BigDecimal number) {
    clear();
    this.valueNumber = number;
  }

  public void assignBoolean(boolean flag) {
    clear();
    this.valueBoolean = flag;
  }

  public void assignDate(LocalDate date) {
    clear();
    this.valueDate = date;
  }

  public void archive() {
    this.archived = true;
  }

  private void clear() {
    this.valueText = null;
    this.valueNumber = null;
    this.valueBoolean = null;
    this.valueDate = null;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
