package com.sairo.be.domain.legal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "company_service_info")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanyServiceInfo {

  @Id private Long id;

  @Column(name = "version_no", nullable = false)
  private int versionNo;

  @Column(name = "business_name", nullable = false, length = 200)
  private String businessName;

  @Column(name = "representative_name", nullable = false, length = 100)
  private String representativeName;

  @Column(name = "business_number", nullable = false, length = 20)
  private String businessNumber;

  @Column(nullable = false, columnDefinition = "text")
  private String address;

  @Column(name = "contact_phone", nullable = false, length = 30)
  private String contactPhone;

  @Column(name = "ecommerce_notice", length = 100)
  private String ecommerceNotice;

  @Column(name = "support_email", nullable = false, length = 254)
  private String supportEmail;

  @Column(name = "privacy_contact", nullable = false, columnDefinition = "text")
  private String privacyContact;

  @Column(name = "privacy_request_path", nullable = false, columnDefinition = "text")
  private String privacyRequestPath;

  @Column(name = "published_at")
  private Instant publishedAt;
}
