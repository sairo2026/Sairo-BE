package com.sairo.be.domain.billing.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 예약된 요금표 버전과 체험 이벤트를 효력·시작 시각에 시행·활성으로 바꾼다. */
@Profile("!migrate")
@Repository
public class PriceScheduleActivationRepository {

  private final JdbcClient jdbcClient;

  public PriceScheduleActivationRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public int activateDuePriceSchedules(Instant now) {
    return jdbcClient
        .sql(
            "UPDATE price_schedule SET status = 'ACTIVE' WHERE status = 'SCHEDULED'"
                + " AND effective_at <= :now")
        .param("now", Timestamp.from(now))
        .update();
  }

  public int activateDueTrialEvents(Instant now) {
    return jdbcClient
        .sql(
            "UPDATE trial_event SET status = 'ACTIVE' WHERE status = 'SCHEDULED'"
                + " AND starts_at <= :now")
        .param("now", Timestamp.from(now))
        .update();
  }
}
