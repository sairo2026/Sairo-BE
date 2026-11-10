package com.sairo.be.domain.customer.entity;

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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "is_corporation", nullable = false)
  private boolean corporation;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(name = "managing_membership_id", nullable = false)
  private Long managingMembershipId;

  @Column(length = 30)
  private String phone;

  @Column(name = "secondary_phone", length = 30)
  private String secondaryPhone;

  @Column(length = 254)
  private String email;

  @Column(columnDefinition = "text")
  private String memo;

  @Column(name = "data_source", nullable = false, length = 100)
  private String dataSource;

  @Enumerated(EnumType.STRING)
  @Column(name = "processing_basis", nullable = false, length = 30)
  private ProcessingBasis processingBasis;

  @Column(name = "data_purpose", nullable = false, length = 200)
  private String dataPurpose;

  @Column(name = "retention_until", nullable = false)
  private LocalDate retentionUntil;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static Customer register(
      Long officeId,
      boolean corporation,
      String name,
      Long managingMembershipId,
      String dataSource,
      ProcessingBasis processingBasis,
      String dataPurpose,
      LocalDate retentionUntil) {
    Customer customer = new Customer();
    customer.officeId = officeId;
    customer.corporation = corporation;
    customer.name = name;
    customer.managingMembershipId = managingMembershipId;
    customer.dataSource = dataSource;
    customer.processingBasis = processingBasis;
    customer.dataPurpose = dataPurpose;
    customer.retentionUntil = retentionUntil;
    return customer;
  }

  public void changeName(String name) {
    this.name = name;
  }

  public void changeContact(String phone, String secondaryPhone, String email) {
    this.phone = phone;
    this.secondaryPhone = secondaryPhone;
    this.email = email;
  }

  public void changeMemo(String memo) {
    this.memo = memo;
  }

  public void changeManager(Long managingMembershipId) {
    this.managingMembershipId = managingMembershipId;
  }

  public void changeCollection(
      String dataSource,
      ProcessingBasis processingBasis,
      String dataPurpose,
      LocalDate retentionUntil) {
    this.dataSource = dataSource;
    this.processingBasis = processingBasis;
    this.dataPurpose = dataPurpose;
    this.retentionUntil = retentionUntil;
  }

  public void archive(Instant at) {
    this.deletedAt = at;
  }

  public void restore() {
    this.deletedAt = null;
  }

  public void touch() {
    this.updatedAt = Instant.now();
  }

  public boolean isArchived() {
    return deletedAt != null;
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
