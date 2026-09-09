package com.sairo.be.domain.office.entity;

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
@Table(name = "office")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Office {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(name = "representative_name", nullable = false, length = 50)
  private String representativeName;

  @Column(name = "business_registration_number", nullable = false, unique = true, length = 10)
  private String businessRegistrationNumber;

  @Column(name = "real_estate_license_number", nullable = false, unique = true, length = 30)
  private String realEstateLicenseNumber;

  @Column(nullable = false, length = 30)
  private String phone;

  @Column(nullable = false, length = 300)
  private String address;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static Office register(
      String name,
      String representativeName,
      String businessRegistrationNumber,
      String realEstateLicenseNumber,
      String phone,
      String address) {
    Office office = new Office();
    office.name = name;
    office.representativeName = representativeName;
    office.businessRegistrationNumber = businessRegistrationNumber;
    office.realEstateLicenseNumber = realEstateLicenseNumber;
    office.phone = phone;
    office.address = address;
    office.createdAt = Instant.now();
    return office;
  }
}
