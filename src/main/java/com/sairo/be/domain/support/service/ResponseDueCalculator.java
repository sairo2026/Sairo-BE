package com.sairo.be.domain.support.service;

import com.sairo.be.global.support.TimeRules;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;

public final class ResponseDueCalculator {

  private ResponseDueCalculator() {}

  public static Instant firstResponseDue(Instant receivedAt) {
    ZonedDateTime due = TimeRules.toDisplay(receivedAt).plusDays(1);
    while (due.getDayOfWeek() == DayOfWeek.SATURDAY || due.getDayOfWeek() == DayOfWeek.SUNDAY) {
      due = due.plusDays(1);
    }
    return due.toInstant();
  }
}
