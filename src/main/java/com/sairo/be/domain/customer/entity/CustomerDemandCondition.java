package com.sairo.be.domain.customer.entity;

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
@Table(name = "customer_demand_condition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerDemandCondition {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private Long customerId;

  @Column(name = "deal_type", nullable = false, length = 10)
  private String dealType;

  @Column(name = "category_id")
  private Long categoryId;

  @Column(name = "price_min")
  private Long priceMin;

  @Column(name = "price_max")
  private Long priceMax;

  @Column(name = "deposit_min")
  private Long depositMin;

  @Column(name = "deposit_max")
  private Long depositMax;

  @Column(name = "rent_min")
  private Long rentMin;

  @Column(name = "rent_max")
  private Long rentMax;

  @Column(name = "area_min")
  private BigDecimal areaMin;

  @Column(name = "area_max")
  private BigDecimal areaMax;

  @Column(name = "move_in_date")
  private LocalDate moveInDate;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public record Values(
      String dealType,
      Long categoryId,
      Long priceMin,
      Long priceMax,
      Long depositMin,
      Long depositMax,
      Long rentMin,
      Long rentMax,
      BigDecimal areaMin,
      BigDecimal areaMax,
      LocalDate moveInDate) {}

  public static CustomerDemandCondition create(Long customerId, Values values) {
    CustomerDemandCondition condition = new CustomerDemandCondition();
    condition.customerId = customerId;
    condition.apply(values);
    return condition;
  }

  public Values values() {
    return new Values(
        dealType,
        categoryId,
        priceMin,
        priceMax,
        depositMin,
        depositMax,
        rentMin,
        rentMax,
        areaMin,
        areaMax,
        moveInDate);
  }

  public void apply(Values values) {
    this.dealType = values.dealType();
    this.categoryId = values.categoryId();
    this.priceMin = values.priceMin();
    this.priceMax = values.priceMax();
    this.depositMin = values.depositMin();
    this.depositMax = values.depositMax();
    this.rentMin = values.rentMin();
    this.rentMax = values.rentMax();
    this.areaMin = values.areaMin();
    this.areaMax = values.areaMax();
    this.moveInDate = values.moveInDate();
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
