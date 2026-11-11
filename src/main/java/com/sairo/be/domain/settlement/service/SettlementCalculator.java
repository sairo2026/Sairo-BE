package com.sairo.be.domain.settlement.service;

import com.sairo.be.domain.settlement.entity.SettlementMetric;
import com.sairo.be.domain.settlement.repository.MonthlySettlementRepository;
import com.sairo.be.domain.settlement.repository.MonthlySettlementRepository.StoredSettlement;
import com.sairo.be.domain.settlement.repository.SettlementMetricRepository;
import com.sairo.be.domain.settlement.repository.SettlementWindow;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 월말결산 값을 원본 사건에서 계산하고 지난 달 저장값을 맞춘다. 지난 달은 계산 결과가 저장값과 다를 때만 저장값을 고치고 집계 생성 시각을 새로 남긴다. 정정이 이 시간축을
 * 바꾸면 다음 계산에서 그 달 값이 바뀐다.
 */
@Profile("!migrate")
@Component
public class SettlementCalculator {

  public record Result(Map<SettlementMetric, Long> values, Instant asOf, boolean finalized) {}

  private final SettlementMetricRepository metricRepository;
  private final MonthlySettlementRepository settlementRepository;
  private final AdvisoryLock advisoryLock;

  public SettlementCalculator(
      SettlementMetricRepository metricRepository,
      MonthlySettlementRepository settlementRepository,
      AdvisoryLock advisoryLock) {
    this.metricRepository = metricRepository;
    this.settlementRepository = settlementRepository;
    this.advisoryLock = advisoryLock;
  }

  public Map<SettlementMetric, Long> compute(SettlementWindow window) {
    Map<SettlementMetric, Long> values = new EnumMap<>(SettlementMetric.class);
    for (SettlementMetric metric : SettlementMetric.values()) {
      values.put(metric, metricRepository.aggregate(metric, window));
    }
    return values;
  }

  /** 호출한 트랜잭션 안에서 계산한다. 지난 달이면 저장값을 만들거나 고친다. */
  public Result settle(Long officeId, YearMonth month, Instant now) {
    Map<SettlementMetric, Long> values = compute(SettlementPeriods.window(officeId, month, now));
    if (!month.isBefore(SettlementPeriods.current(now))) {
      return new Result(values, now, false);
    }
    return new Result(values, store(officeId, month, values, now), true);
  }

  /** 저장값이 있는 지난 달만 다시 계산한다. */
  public void recalculateIfStored(Long officeId, YearMonth month, Instant now) {
    if (!month.isBefore(SettlementPeriods.current(now))
        || !settlementRepository.exists(officeId, month)) {
      return;
    }
    store(officeId, month, compute(SettlementPeriods.window(officeId, month, now)), now);
  }

  private Instant store(
      Long officeId, YearMonth month, Map<SettlementMetric, Long> values, Instant now) {
    advisoryLock.lockForTransaction("settlement:" + officeId + ":" + month);
    Optional<StoredSettlement> stored = settlementRepository.lock(officeId, month);
    if (stored.isEmpty()) {
      settlementRepository.insert(officeId, month, values, now);
      return now;
    }
    if (stored.get().values().equals(values)) {
      return stored.get().generatedAt();
    }
    settlementRepository.update(officeId, month, values, now);
    return now;
  }
}
