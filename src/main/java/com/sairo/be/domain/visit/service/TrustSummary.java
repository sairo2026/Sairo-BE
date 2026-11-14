package com.sairo.be.domain.visit.service;

import java.util.List;

public record TrustSummary(
    String state,
    String overallBand,
    int completedVisitCount,
    int evaluatedVisitCount,
    int distinctApproverCount,
    List<Indicator> indicators) {

  public static final String POLICY_VERSION = "TRUST_V1";
  public static final String NO_DATA = "NO_DATA";
  public static final String INSUFFICIENT = "INSUFFICIENT";
  public static final String AVAILABLE = "AVAILABLE";
  public static final int WINDOW_MONTHS = 12;
  public static final int MIN_EVALUATED_VISITS = 5;
  public static final int MIN_DISTINCT_APPROVALS = 3;
  public static final int MIN_ITEM_ANSWERS = 5;

  public record Indicator(String indicator, int answeredCount, int yesCount, String band) {}

  public static String bandOf(int yes, int answered) {
    double ratio = (double) yes / answered;
    if (ratio >= 0.90) {
      return "HIGH";
    }
    return ratio >= 0.70 ? "MID" : "LOW";
  }
}
