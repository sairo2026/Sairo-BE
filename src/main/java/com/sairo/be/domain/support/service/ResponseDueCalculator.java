package com.sairo.be.domain.support.service;

import com.sairo.be.domain.support.entity.InquiryPriority;
import com.sairo.be.global.support.TimeRules;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

public final class ResponseDueCalculator {

  private ResponseDueCalculator() {}

  public static final Duration HIGH_PRIORITY_TARGET = Duration.ofHours(4);

  /** 우선순위별 첫 답변 목표다. 높음은 4시간, 보통은 1영업일, 낮음은 2영업일이다. */
  public static Instant firstResponseDue(Instant receivedAt, InquiryPriority priority) {
    return switch (priority) {
      case HIGH -> receivedAt.plus(HIGH_PRIORITY_TARGET);
      case NORMAL -> firstResponseDue(receivedAt);
      case LOW -> nextBusinessDay(nextBusinessDay(TimeRules.toDisplay(receivedAt))).toInstant();
    };
  }

  private static ZonedDateTime nextBusinessDay(ZonedDateTime from) {
    ZonedDateTime due = from.plusDays(1);
    while (due.getDayOfWeek() == DayOfWeek.SATURDAY || due.getDayOfWeek() == DayOfWeek.SUNDAY) {
      due = due.plusDays(1);
    }
    return due;
  }

  public static Instant firstResponseDue(Instant receivedAt) {
    ZonedDateTime due = TimeRules.toDisplay(receivedAt).plusDays(1);
    while (due.getDayOfWeek() == DayOfWeek.SATURDAY || due.getDayOfWeek() == DayOfWeek.SUNDAY) {
      due = due.plusDays(1);
    }
    return due.toInstant();
  }
}
