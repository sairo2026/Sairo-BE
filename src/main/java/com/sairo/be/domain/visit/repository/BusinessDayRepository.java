package com.sairo.be.domain.visit.repository;

import com.sairo.be.global.support.TimeRules;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class BusinessDayRepository {

  static final LocalTime DUE_TIME = LocalTime.of(18, 0);
  private static final int LOOKAHEAD_DAYS = 60;

  private final JdbcClient jdbcClient;

  public BusinessDayRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Instant dueAfter(Instant from, int businessDays) {
    LocalDate start = TimeRules.displayDate(from);
    Map<LocalDate, Boolean> overrides = new HashMap<>();
    jdbcClient
        .sql(
            "SELECT calendar_date, is_business_day FROM ops_business_calendar WHERE"
                + " calendar_date > :start AND calendar_date <= :end")
        .param("start", Date.valueOf(start))
        .param("end", Date.valueOf(start.plusDays(LOOKAHEAD_DAYS)))
        .query(
            (rs, rowNum) ->
                overrides.put(
                    rs.getDate("calendar_date").toLocalDate(), rs.getBoolean("is_business_day")))
        .list();
    LocalDate day = start;
    int counted = 0;
    while (counted < businessDays) {
      day = day.plusDays(1);
      boolean weekday =
          day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
      if (overrides.getOrDefault(day, weekday)) {
        counted++;
      }
    }
    return day.atTime(DUE_TIME).atZone(TimeRules.DISPLAY_ZONE).toInstant();
  }
}
