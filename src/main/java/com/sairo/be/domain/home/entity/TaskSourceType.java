package com.sairo.be.domain.home.entity;

public enum TaskSourceType {
  MANUAL,
  VISIT,
  CONTRACT_BALANCE,
  CONTRACT_MATURITY;

  public boolean isAutomatic() {
    return this != MANUAL;
  }

  public boolean isContract() {
    return this == CONTRACT_BALANCE || this == CONTRACT_MATURITY;
  }
}
