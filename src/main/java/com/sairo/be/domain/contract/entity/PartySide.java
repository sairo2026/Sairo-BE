package com.sairo.be.domain.contract.entity;

public enum PartySide {
  SELLER_LANDLORD("sellerLandlordParties"),
  BUYER_TENANT("buyerTenantParties");

  private final String field;

  PartySide(String field) {
    this.field = field;
  }

  public String field() {
    return field;
  }
}
