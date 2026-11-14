package com.sairo.be.domain.contract.entity;

public enum DealType {
  SALE,
  JEONSE,
  MONTHLY;

  public boolean isLease() {
    return this != SALE;
  }
}
