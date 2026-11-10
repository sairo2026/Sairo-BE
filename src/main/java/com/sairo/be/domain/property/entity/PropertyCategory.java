package com.sairo.be.domain.property.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "property_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyCategory {

  private static final String CUSTOM_CODE_PREFIX = "CUSTOM_";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", updatable = false)
  private Long officeId;

  @Column(nullable = false, length = 30, updatable = false)
  private String code;

  @Column(nullable = false, length = 50)
  private String name;

  @Column(name = "is_system", nullable = false, updatable = false)
  private boolean system;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static PropertyCategory custom(Long officeId, String name) {
    PropertyCategory category = new PropertyCategory();
    category.officeId = officeId;
    category.code =
        CUSTOM_CODE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    category.name = name;
    category.system = false;
    category.active = true;
    return category;
  }

  public void rename(String name) {
    this.name = name;
  }

  public void changeActive(boolean active) {
    this.active = active;
  }

  public boolean isVisibleTo(Long officeId) {
    return system || this.officeId.equals(officeId);
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
