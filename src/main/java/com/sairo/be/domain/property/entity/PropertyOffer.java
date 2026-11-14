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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "property_offer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyOffer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "property_id", nullable = false, updatable = false)
  private Long propertyId;

  @Enumerated(EnumType.STRING)
  @Column(name = "deal_type", nullable = false, length = 10, updatable = false)
  private DealType dealType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private OfferStatus status;

  @Column(name = "sale_price")
  private Long salePrice;

  @Column(name = "deposit_amount")
  private Long depositAmount;

  @Column(name = "monthly_rent_amount")
  private Long monthlyRentAmount;

  @Column(name = "premium_amount")
  private Long premiumAmount;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static PropertyOffer open(
      Long propertyId,
      DealType dealType,
      Long salePrice,
      Long depositAmount,
      Long monthlyRentAmount,
      Long premiumAmount) {
    PropertyOffer offer = new PropertyOffer();
    offer.propertyId = propertyId;
    offer.dealType = dealType;
    offer.status = OfferStatus.AVAILABLE;
    offer.changePrices(salePrice, depositAmount, monthlyRentAmount, premiumAmount);
    return offer;
  }

  public void changePrices(
      Long salePrice, Long depositAmount, Long monthlyRentAmount, Long premiumAmount) {
    this.salePrice = salePrice;
    this.depositAmount = depositAmount;
    this.monthlyRentAmount = monthlyRentAmount;
    this.premiumAmount = premiumAmount;
  }

  public void changeStatus(OfferStatus status) {
    this.status = status;
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
