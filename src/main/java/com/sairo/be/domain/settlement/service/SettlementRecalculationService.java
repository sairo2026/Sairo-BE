package com.sairo.be.domain.settlement.service;

import com.sairo.be.domain.settlement.repository.SettlementSourceRepository;
import com.sairo.be.global.port.SettlementRecalculator;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 정정을 처리한 업무 트랜잭션 안에서 영향받은 지난 달의 저장된 결산을 다시 계산한다. */
@Profile("!migrate")
@Service
public class SettlementRecalculationService implements SettlementRecalculator {

  private final SettlementCalculator calculator;
  private final SettlementSourceRepository sourceRepository;
  private final Clock clock;

  public SettlementRecalculationService(
      SettlementCalculator calculator, SettlementSourceRepository sourceRepository, Clock clock) {
    this.calculator = calculator;
    this.sourceRepository = sourceRepository;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void recalculate(Long officeId, Collection<Instant> effectiveTimes) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    for (YearMonth month : months(effectiveTimes)) {
      calculator.recalculateIfStored(officeId, month, now);
    }
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void recalculateForCoordination(
      Long officeId, Long coordinationId, Collection<Instant> effectiveTimes) {
    Set<YearMonth> months = months(effectiveTimes);
    sourceRepository
        .firstFinalAt(officeId, coordinationId)
        .ifPresent(endedAt -> months.add(SettlementPeriods.of(endedAt)));
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    for (YearMonth month : months) {
      calculator.recalculateIfStored(officeId, month, now);
    }
  }

  private static Set<YearMonth> months(Collection<Instant> times) {
    Set<YearMonth> months = new TreeSet<>();
    times.stream().filter(Objects::nonNull).map(SettlementPeriods::of).forEach(months::add);
    return months;
  }
}
