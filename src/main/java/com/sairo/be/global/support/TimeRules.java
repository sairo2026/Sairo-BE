package com.sairo.be.global.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public final class TimeRules {

  public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");
  public static final ZoneId STORAGE_ZONE = ZoneOffset.UTC;

  private TimeRules() {}

  public static ZonedDateTime toDisplay(Instant instant) {
    return instant.atZone(DISPLAY_ZONE);
  }

  public static java.time.OffsetDateTime toDisplayOffset(Instant instant) {
    return instant == null ? null : toDisplay(instant).toOffsetDateTime();
  }

  public static LocalDate displayDate(Instant instant) {
    return toDisplay(instant).toLocalDate();
  }

  public static Instant startOfDisplayDay(LocalDate date) {
    return date.atStartOfDay(DISPLAY_ZONE).toInstant();
  }

  public static Instant endOfDisplayDayExclusive(LocalDate date) {
    return date.plusDays(1).atStartOfDay(DISPLAY_ZONE).toInstant();
  }

  public static boolean overlaps(
      Instant firstStart, Instant firstEnd, Instant secondStart, Instant secondEnd) {
    return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
  }

  public static boolean isDateOnlyExpired(LocalDate lastValidDate, Instant now) {
    return !now.isBefore(endOfDisplayDayExclusive(lastValidDate));
  }
}
