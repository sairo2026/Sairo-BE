package com.sairo.be.domain.legal.entity;

import java.io.Serializable;
import java.util.Objects;

public class TermsCatalogId implements Serializable {

  private String termsCode;
  private String termsVersion;

  protected TermsCatalogId() {}

  public TermsCatalogId(String termsCode, String termsVersion) {
    this.termsCode = termsCode;
    this.termsVersion = termsVersion;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof TermsCatalogId that)) {
      return false;
    }
    return Objects.equals(termsCode, that.termsCode)
        && Objects.equals(termsVersion, that.termsVersion);
  }

  @Override
  public int hashCode() {
    return Objects.hash(termsCode, termsVersion);
  }
}
