package com.sairo.be.domain.auth.controller;

final class KakaoLandingPath {

  private static final String OWNER = "OWNER";

  private KakaoLandingPath() {}

  static String of(String accessCondition, String role) {
    boolean owner = OWNER.equals(role);
    return switch (accessCondition) {
      case "ACTIVE" -> "/";
      case "PRE_VERIFICATION" -> "/office/start";
      case "OFFICE_UNDER_REVIEW" -> "/office/review";
      case "VERIFIED_UNPAID" -> owner ? "/billing" : "/access";
      case "PAYMENT_OVERDUE" -> owner ? "/billing" : "/";
      case "AUTH_REVOKED" -> "/office/verification";
      case "REMOVED" -> "/removed";
      default -> "/return";
    };
  }
}
