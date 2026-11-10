package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.legal.entity.TermsCatalog;
import com.sairo.be.domain.legal.entity.TermsCategory;
import java.time.Instant;

public record TermsVersionView(
    String termsCode,
    String version,
    String title,
    TermsCategory category,
    boolean required,
    String documentUrl,
    Instant effectiveAt,
    boolean reconsentRequired,
    String changeSummary) {

  public static TermsVersionView from(TermsCatalog catalog) {
    return new TermsVersionView(
        catalog.getTermsCode(),
        catalog.getTermsVersion(),
        catalog.getTitle(),
        catalog.getCategory(),
        catalog.isRequired(),
        catalog.getDocumentUrl(),
        catalog.getEffectiveAt(),
        catalog.isReconsentRequired(),
        catalog.getChangeSummary());
  }

  public boolean isAgreeable() {
    return category != TermsCategory.NOTICE;
  }
}
