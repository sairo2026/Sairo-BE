package com.sairo.be.domain.property.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "property")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Property {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false)
  private Long officeId;

  @Column(nullable = false, length = 300)
  private String address;

  @Column(name = "address_detail", length = 100)
  private String addressDetail;

  @Column(name = "property_name", length = 100)
  private String propertyName;

  @Enumerated(EnumType.STRING)
  @Column(name = "deal_type", nullable = false, length = 10)
  private PropertyDealType dealType;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  private Property(
      Long officeId,
      String address,
      String addressDetail,
      String propertyName,
      PropertyDealType dealType) {
    this.officeId = officeId;
    this.address = address;
    this.addressDetail = addressDetail;
    this.propertyName = propertyName;
    this.dealType = dealType;
  }

  public static Property register(
      Long officeId,
      String address,
      String addressDetail,
      String propertyName,
      PropertyDealType dealType) {
    return new Property(officeId, address, addressDetail, propertyName, dealType);
  }

  public void updateBasicInfo(String address, String addressDetail, String propertyName) {
    this.address = address;
    this.addressDetail = addressDetail;
    this.propertyName = propertyName;
  }
}
