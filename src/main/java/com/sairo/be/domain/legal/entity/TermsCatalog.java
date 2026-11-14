package com.sairo.be.domain.legal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "terms_catalog")
@IdClass(TermsCatalogId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsCatalog {

  @Id
  @Column(name = "terms_code", nullable = false, length = 30)
  private String termsCode;

  @Id
  @Column(name = "terms_version", nullable = false, length = 20)
  private String termsVersion;

  @Column(nullable = false, length = 100)
  private String title;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private TermsCategory category;

  @Column(nullable = false)
  private boolean required;

  @Column(name = "document_url", nullable = false, length = 500)
  private String documentUrl;

  @Column(name = "effective_at", nullable = false)
  private Instant effectiveAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "display_context", nullable = false, length = 20)
  private TermsDisplayContext displayContext;

  @Column(name = "reconsent_required", nullable = false)
  private boolean reconsentRequired;

  @Column(name = "change_summary", columnDefinition = "text")
  private String changeSummary;
}
