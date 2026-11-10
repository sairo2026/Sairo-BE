package com.sairo.be.domain.property.entity;

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
@Table(name = "property_photo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyPhoto {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "property_id", nullable = false, updatable = false)
  private Long propertyId;

  @Column(name = "object_key", nullable = false, length = 500, updatable = false)
  private String objectKey;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static PropertyPhoto of(
      Long propertyId, String objectKey, String description, int sortOrder) {
    PropertyPhoto photo = new PropertyPhoto();
    photo.propertyId = propertyId;
    photo.objectKey = objectKey;
    photo.description = description;
    photo.sortOrder = sortOrder;
    return photo;
  }

  public void changeDescription(String description) {
    this.description = description;
  }

  public void changeSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
