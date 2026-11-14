package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.FailureCategory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public final class FailureCategories {

  private static final List<String> UNUSABLE_MARKERS =
      List.of(
          "INVALID_CARD",
          "STOPPED_CARD",
          "EXPIRED_CARD",
          "NOT_SUPPORTED_CARD",
          "INVALID_STOPPED",
          "LOST_STOLEN",
          "STUB_UNUSABLE");
  private static final List<String> FUNDS_MARKERS =
      List.of("BALANCE", "LIMIT", "INSUFFICIENT", "EXCEED");
  private static final List<Duration> RETRY_OFFSETS =
      List.of(Duration.ofHours(1), Duration.ofDays(3), Duration.ofDays(7));

  private FailureCategories() {}

  public static FailureCategory from(String failureCode) {
    if (failureCode == null || failureCode.isBlank()) {
      return FailureCategory.OTHER;
    }
    String code = failureCode.toUpperCase(Locale.ROOT);
    if (code.contains("TIMEOUT")) {
      return FailureCategory.TIMEOUT;
    }
    if (UNUSABLE_MARKERS.stream().anyMatch(code::contains)) {
      return FailureCategory.CARD_UNUSABLE;
    }
    if (FUNDS_MARKERS.stream().anyMatch(code::contains)) {
      return FailureCategory.INSUFFICIENT_FUNDS;
    }
    return FailureCategory.OTHER;
  }

  /** 결제 실패 안내에 넣는 사유 문구다. */
  public static String label(FailureCategory category) {
    if (category == null) {
      return "결제사 처리 실패";
    }
    return switch (category) {
      case CARD_UNUSABLE -> "카드 사용 불가";
      case INSUFFICIENT_FUNDS -> "잔액 또는 한도 부족";
      case TIMEOUT -> "결제사 응답 지연";
      case OTHER -> "결제사 처리 실패";
    };
  }

  public static Instant nextRetryAt(Instant dueAt, int failedAttemptNo, FailureCategory category) {
    if (category == FailureCategory.CARD_UNUSABLE
        || failedAttemptNo < 1
        || failedAttemptNo > RETRY_OFFSETS.size()) {
      return null;
    }
    return dueAt.plus(RETRY_OFFSETS.get(failedAttemptNo - 1));
  }
}
