package com.sairo.be.domain.office.entity;

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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "office")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Office {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false, length = 12)
  private OfficeEntityType entityType;

  @Column(name = "registration_authority", nullable = false, length = 100)
  private String registrationAuthority;

  @Column(name = "real_estate_license_number", nullable = false, length = 30)
  private String realEstateLicenseNumber;

  @Column(name = "business_registration_number", nullable = false, length = 10)
  private String businessRegistrationNumber;

  @Column(name = "representative_name", nullable = false, length = 50)
  private String representativeName;

  @Column(name = "representative_phone", nullable = false, length = 30)
  private String representativePhone;

  @Column(nullable = false, length = 300)
  private String address;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static Office register(
      String name,
      OfficeEntityType entityType,
      String registrationAuthority,
      String realEstateLicenseNumber,
      String businessRegistrationNumber,
      String representativeName,
      String representativePhone,
      String address) {
    Office office = new Office();
    office.name = name;
    office.entityType = entityType;
    office.registrationAuthority = registrationAuthority;
    office.realEstateLicenseNumber = realEstateLicenseNumber;
    office.businessRegistrationNumber = businessRegistrationNumber;
    office.representativeName = representativeName;
    office.representativePhone = representativePhone;
    office.address = address;
    return office;
  }

  public void changeRepresentativePhone(String representativePhone) {
    this.representativePhone = representativePhone;
  }

  public void changeAddress(String address) {
    this.address = address;
  }

  public void changeRegistrationInfo(
      OfficeEntityType entityType,
      String registrationAuthority,
      String realEstateLicenseNumber,
      String businessRegistrationNumber) {
    this.entityType = entityType;
    this.registrationAuthority = registrationAuthority;
    this.realEstateLicenseNumber = realEstateLicenseNumber;
    this.businessRegistrationNumber = businessRegistrationNumber;
  }

  public void changeRepresentative(String representativeName, String representativePhone) {
    this.representativeName = representativeName;
    this.representativePhone = representativePhone;
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
