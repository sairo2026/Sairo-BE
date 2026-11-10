package com.sairo.be.global.security;

public enum StaffRole {
  OWNER("대표"),
  AGENT("소속공인중개사"),
  DEPUTY("중개보조원");

  private final String legalTitle;

  StaffRole(String legalTitle) {
    this.legalTitle = legalTitle;
  }

  public String legalTitle() {
    return legalTitle;
  }

  public boolean isAgentOrOwner() {
    return this == OWNER || this == AGENT;
  }
}
