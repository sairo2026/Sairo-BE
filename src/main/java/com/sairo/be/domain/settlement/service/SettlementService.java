package com.sairo.be.domain.settlement.service;

import com.sairo.be.domain.settlement.dto.response.MonthlySettlementListResponse;
import com.sairo.be.domain.settlement.dto.response.MonthlySettlementResponse;
import com.sairo.be.domain.settlement.dto.response.SettlementDetailsResponse;
import com.sairo.be.domain.settlement.entity.SettlementMetric;
import com.sairo.be.domain.settlement.repository.SettlementMetricRepository;
import com.sairo.be.domain.settlement.repository.SettlementSourceRepository;
import com.sairo.be.domain.settlement.repository.SettlementWindow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class SettlementService {

  private final SettlementCalculator calculator;
  private final SettlementMetricRepository metricRepository;
  private final SettlementSourceRepository sourceRepository;
  private final Clock clock;

  public SettlementService(
      SettlementCalculator calculator,
      SettlementMetricRepository metricRepository,
      SettlementSourceRepository sourceRepository,
      Clock clock) {
    this.calculator = calculator;
    this.metricRepository = metricRepository;
    this.sourceRepository = sourceRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public MonthlySettlementListResponse list(StaffPrincipal principal) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    YearMonth current = SettlementPeriods.current(now);
    YearMonth earliest = earliest(principal.officeId(), current);
    List<MonthlySettlementListResponse.Item> items = new ArrayList<>();
    for (YearMonth month = current; !month.isBefore(earliest); month = month.minusMonths(1)) {
      long signed =
          metricRepository.aggregate(
              SettlementMetric.contractSignedCount,
              SettlementPeriods.window(principal.officeId(), month, now));
      items.add(
          new MonthlySettlementListResponse.Item(
              month.toString(), month.isBefore(current), signed));
    }
    return new MonthlySettlementListResponse(items);
  }

  @Transactional
  public MonthlySettlementResponse get(StaffPrincipal principal, String yearMonth) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    YearMonth month = month(principal.officeId(), yearMonth, now);
    SettlementCalculator.Result result = calculator.settle(principal.officeId(), month, now);
    return toResponse(month, result);
  }

  @Transactional(readOnly = true)
  public SettlementDetailsResponse details(
      StaffPrincipal principal, String yearMonth, String metricName, PageQuery page) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    YearMonth month = month(principal.officeId(), yearMonth, now);
    SettlementMetric metric =
        SettlementMetric.parse(metricName)
            .orElseThrow(
                () ->
                    BusinessException.withFieldErrors(
                        ErrorCode.INVALID_REQUEST,
                        List.of(
                            new FieldErrorItem(
                                "metric",
                                metricName == null
                                    ? FieldErrorCode.REQUIRED
                                    : FieldErrorCode.NOT_ALLOWED,
                                "metric은 결산 지표 이름 중 하나여야 합니다."))));
    SettlementWindow window = SettlementPeriods.window(principal.officeId(), month, now);
    long total = metricRepository.countRows(metric, window);
    List<Map<String, Object>> items =
        metricRepository.rows(metric, window, page.size(), (long) page.page() * (long) page.size());
    return new SettlementDetailsResponse(
        metric.name(), month.toString(), items, page.page(), page.size(), total);
  }

  @Transactional
  public String exportCsv(StaffPrincipal principal, String yearMonth) {
    return SettlementCsv.render(get(principal, yearMonth));
  }

  YearMonth month(Long officeId, String yearMonth, Instant now) {
    YearMonth current = SettlementPeriods.current(now);
    return SettlementPeriods.parse(yearMonth, earliest(officeId, current), current);
  }

  private YearMonth earliest(Long officeId, YearMonth current) {
    return sourceRepository.officeCreatedAt(officeId).map(SettlementPeriods::of).orElse(current);
  }

  private static MonthlySettlementResponse toResponse(
      YearMonth month, SettlementCalculator.Result result) {
    Map<SettlementMetric, Long> v = result.values();
    return new MonthlySettlementResponse(
        month.toString(),
        result.finalized(),
        v.get(SettlementMetric.propertyCountTotal),
        v.get(SettlementMetric.customerCountTotal),
        v.get(SettlementMetric.propertyCountNew),
        v.get(SettlementMetric.customerCountNew),
        v.get(SettlementMetric.firstInquiryCount),
        v.get(SettlementMetric.visitCompletedCount),
        v.get(SettlementMetric.visitCompletedPropertyCount),
        v.get(SettlementMetric.coordinationEndedCount),
        v.get(SettlementMetric.coordinationVisitCompletedCount),
        v.get(SettlementMetric.coordinationVisitIncompleteCount),
        v.get(SettlementMetric.contractSignedCount),
        v.get(SettlementMetric.contractSignedPropertyCount),
        v.get(SettlementMetric.leaseMaturityCount),
        v.get(SettlementMetric.leaseMaturityPropertyCount),
        v.get(SettlementMetric.agreedFeeTotal),
        v.get(SettlementMetric.actualFeeReceivedTotal),
        v.get(SettlementMetric.outstandingAmount),
        v.get(SettlementMetric.overcollectedAmount),
        v.get(SettlementMetric.propertyCountNewMigrated),
        v.get(SettlementMetric.customerCountNewMigrated),
        v.get(SettlementMetric.agreedFeeUndatedCount),
        TimeRules.toDisplayOffset(result.asOf()));
  }
}
