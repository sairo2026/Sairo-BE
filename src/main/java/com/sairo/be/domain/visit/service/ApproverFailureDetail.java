package com.sairo.be.domain.visit.service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class ApproverFailureDetail {

  private static final String PREFIX = "위반한 규칙: ";
  private static final String GENERIC = "신청한 사무소 또는 고객 쪽 사정으로 확정할 수 없다.";
  private static final Set<String> APPROVER_RULES =
      Set.of(
          ApproverConditionEvaluator.CONDITION_EXPIRED,
          "CONDITION_CHANGED",
          ApproverConditionEvaluator.WINDOW_MISMATCH,
          ApproverConditionEvaluator.MAX_VISITS_PER_DAY,
          ApproverConditionEvaluator.MIN_GAP,
          ApproverConditionEvaluator.SINGLE_VISIT_MAX);

  private ApproverFailureDetail() {}

  public static String of(String storedDetail) {
    if (storedDetail == null) {
      return null;
    }
    if (!storedDetail.startsWith(PREFIX)) {
      return GENERIC;
    }
    List<String> rules = Arrays.asList(storedDetail.substring(PREFIX.length()).split(", "));
    List<String> visible = rules.stream().filter(APPROVER_RULES::contains).toList();
    if (visible.isEmpty()) {
      return GENERIC;
    }
    String shown = PREFIX + String.join(", ", visible);
    return visible.size() == rules.size() ? shown : shown + ". " + GENERIC;
  }
}
