package com.sairo.be.domain.property.entity;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "property")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Property {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "category_id", nullable = false)
  private Long categoryId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 300)
  private String address;

  @Column(name = "address_detail", length = 100)
  private String addressDetail;

  @Column(name = "exclusive_area_sqm", precision = 12, scale = 2)
  private BigDecimal exclusiveAreaSqm;

  @Column(name = "supply_area_sqm", precision = 12, scale = 2)
  private BigDecimal supplyAreaSqm;

  @Column(name = "land_area_sqm", precision = 12, scale = 2)
  private BigDecimal landAreaSqm;

  @Column(name = "gross_floor_area_sqm", precision = 12, scale = 2)
  private BigDecimal grossFloorAreaSqm;

  @Column(name = "move_in_date")
  private LocalDate moveInDate;

  @Column(name = "move_in_negotiable", nullable = false)
  private boolean moveInNegotiable;

  @Column(name = "maintenance_fee_amount")
  private Long maintenanceFeeAmount;

  @Column(name = "maintenance_fee_items", columnDefinition = "text")
  private String maintenanceFeeItems;

  @Enumerated(EnumType.STRING)
  @Column(name = "occupancy_status", nullable = false, length = 20)
  private OccupancyStatus occupancyStatus;

  @Column(columnDefinition = "text")
  private String description;

  @Column(columnDefinition = "text")
  private String memo;

  @Column(name = "reference_lease_end_date")
  private LocalDate referenceLeaseEndDate;

  @Column(name = "reference_lease_end_source", length = 200)
  private String referenceLeaseEndSource;

  @Column(name = "managing_membership_id")
  private Long managingMembershipId;

  @Column(name = "duplicate_override_reason", columnDefinition = "text")
  private String duplicateOverrideReason;

  @Enumerated(EnumType.STRING)
  @Column(name = "registration_stage", nullable = false, length = 10)
  private RegistrationStage registrationStage;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static Property draft(Long officeId, Long categoryId, PropertyAttributes attributes) {
    Property property = new Property();
    property.officeId = officeId;
    property.categoryId = categoryId;
    property.registrationStage = RegistrationStage.DRAFT;
    property.applyAttributes(attributes);
    return property;
  }

  public void applyAttributes(PropertyAttributes attributes) {
    this.name = attributes.name();
    this.address = attributes.address();
    this.addressDetail = attributes.addressDetail();
    this.managingMembershipId = attributes.managingMembershipId();
    this.exclusiveAreaSqm = attributes.exclusiveAreaSqm();
    this.supplyAreaSqm = attributes.supplyAreaSqm();
    this.landAreaSqm = attributes.landAreaSqm();
    this.grossFloorAreaSqm = attributes.grossFloorAreaSqm();
    this.moveInDate = attributes.moveInDate();
    this.moveInNegotiable = attributes.moveInNegotiable();
    this.maintenanceFeeAmount = attributes.maintenanceFeeAmount();
    this.maintenanceFeeItems = attributes.maintenanceFeeItems();
    this.occupancyStatus = attributes.occupancyStatus();
    this.description = attributes.description();
    this.memo = attributes.memo();
    this.referenceLeaseEndDate = attributes.referenceLeaseEndDate();
    this.referenceLeaseEndSource = attributes.referenceLeaseEndSource();
    this.duplicateOverrideReason = attributes.duplicateOverrideReason();
  }

  public PropertyAttributes attributes() {
    return new PropertyAttributes(
        name,
        address,
        addressDetail,
        managingMembershipId,
        exclusiveAreaSqm,
        supplyAreaSqm,
        landAreaSqm,
        grossFloorAreaSqm,
        moveInDate,
        moveInNegotiable,
        maintenanceFeeAmount,
        maintenanceFeeItems,
        occupancyStatus,
        description,
        memo,
        referenceLeaseEndDate,
        referenceLeaseEndSource,
        duplicateOverrideReason);
  }

  public void activate() {
    this.registrationStage = RegistrationStage.ACTIVE;
  }

  public boolean isArchived() {
    return deletedAt != null;
  }

  public boolean isActive() {
    return registrationStage == RegistrationStage.ACTIVE;
  }

  public void touch() {
    this.updatedAt = Instant.now();
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
