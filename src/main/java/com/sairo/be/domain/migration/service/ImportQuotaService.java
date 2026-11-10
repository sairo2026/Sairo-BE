package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.repository.ImportBatchJdbcRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ImportQuotaService {

  public static final int MONTHLY_LIMIT = 5;

  private final ImportBatchJdbcRepository jdbcRepository;
  private final Clock clock;

  public ImportQuotaService(ImportBatchJdbcRepository jdbcRepository, Clock clock) {
    this.jdbcRepository = jdbcRepository;
    this.clock = clock;
  }

  public int remaining(long officeId) {
    return Math.max(0, MONTHLY_LIMIT - used(officeId));
  }

  public void requireAvailable(long officeId) {
    if (used(officeId) >= MONTHLY_LIMIT) {
      throw new BusinessException(ErrorCode.IMPORT_QUOTA_EXCEEDED);
    }
  }

  private int used(long officeId) {
    Instant now = clock.instant();
    LocalDate firstDay = TimeRules.displayDate(now).withDayOfMonth(1);
    return jdbcRepository.countQuotaUsed(
        officeId,
        TimeRules.startOfDisplayDay(firstDay),
        TimeRules.startOfDisplayDay(firstDay.plusMonths(1)));
  }
}
