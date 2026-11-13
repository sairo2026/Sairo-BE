package com.sairo.be.global.config;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "sairo.visit.review")
public record VisitReviewProperties(
    @DefaultValue("14") int reviewWindowDays,
    @DefaultValue("2") int maxRevisions,
    @DefaultValue("30") int disputeWindowDays,
    @DefaultValue("7") int disputeTargetBusinessDays,
    @DefaultValue("18") int disputeDueHour,
    @DefaultValue("5") int preferredBrokerLimit,
    @DefaultValue("90") int approverListWindowDays) {

  static final int SCHEMA_MAX_REVISIONS = 2;

  public VisitReviewProperties {
    requirePositive("review-window-days", reviewWindowDays);
    requirePositive("dispute-window-days", disputeWindowDays);
    requirePositive("dispute-target-business-days", disputeTargetBusinessDays);
    requirePositive("preferred-broker-limit", preferredBrokerLimit);
    requirePositive("approver-list-window-days", approverListWindowDays);
    if (maxRevisions < 0 || maxRevisions > SCHEMA_MAX_REVISIONS) {
      throw new IllegalArgumentException(
          "sairo.visit.review.max-revisions는 0 이상 " + SCHEMA_MAX_REVISIONS + " 이하여야 합니다.");
    }
    if (disputeDueHour < 0 || disputeDueHour > 23) {
      throw new IllegalArgumentException("sairo.visit.review.dispute-due-hour는 0 이상 23 이하여야 합니다.");
    }
  }

  public static VisitReviewProperties defaults() {
    return new VisitReviewProperties(14, 2, 30, 7, 18, 5, 90);
  }

  public LocalTime disputeDueTime() {
    return LocalTime.of(disputeDueHour, 0);
  }

  public Duration reviewWindow() {
    return Duration.ofDays(reviewWindowDays);
  }

  public Duration disputeWindow() {
    return Duration.ofDays(disputeWindowDays);
  }

  public Duration approverListWindow() {
    return Duration.ofDays(approverListWindowDays);
  }

  private static void requirePositive(String name, int value) {
    if (value < 1) {
      throw new IllegalArgumentException("sairo.visit.review." + name + "는 1 이상이어야 합니다.");
    }
  }
}
