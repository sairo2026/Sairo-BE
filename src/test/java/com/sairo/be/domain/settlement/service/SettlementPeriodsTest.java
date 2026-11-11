package com.sairo.be.domain.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.domain.settlement.repository.SettlementWindow;
import com.sairo.be.global.error.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class SettlementPeriodsTest {

  @Test
  void 월_경계는_서울_시간_자정이고_현재_달의_실제_날짜_기준은_오늘이다() {
    Instant now = Instant.parse("2026-11-12T03:00:00Z");
    SettlementWindow current = SettlementPeriods.window(1L, YearMonth.of(2026, 11), now);
    assertThat(current.from()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
    assertThat(current.to()).isEqualTo(Instant.parse("2026-11-30T15:00:00Z"));
    assertThat(current.lastDay()).isEqualTo(LocalDate.of(2026, 11, 30));
    assertThat(current.cutoffDay()).isEqualTo(LocalDate.of(2026, 11, 12));

    SettlementWindow past = SettlementPeriods.window(1L, YearMonth.of(2026, 2), now);
    assertThat(past.cutoffDay()).isEqualTo(LocalDate.of(2026, 2, 28));
  }

  @Test
  void 서울_시간으로_달이_바뀌는_시각을_그_달로_본다() {
    assertThat(SettlementPeriods.of(Instant.parse("2026-10-31T15:00:00Z")))
        .isEqualTo(YearMonth.of(2026, 11));
    assertThat(SettlementPeriods.of(Instant.parse("2026-10-31T14:59:59Z")))
        .isEqualTo(YearMonth.of(2026, 10));
  }

  @Test
  void 형식이_틀리거나_범위를_벗어난_달은_거절한다() {
    YearMonth earliest = YearMonth.of(2026, 3);
    YearMonth current = YearMonth.of(2026, 11);
    assertThat(SettlementPeriods.parse("2026-03", earliest, current)).isEqualTo(earliest);
    for (String bad : new String[] {"2026-3", "2026-00", "2026-13", "26-11", null}) {
      assertThatThrownBy(() -> SettlementPeriods.parse(bad, earliest, current))
          .isInstanceOf(BusinessException.class);
    }
    assertThatThrownBy(() -> SettlementPeriods.parse("2026-12", earliest, current))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> SettlementPeriods.parse("2026-02", earliest, current))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void 내보내기_머리행은_명세_순서이고_부가_표시값_네_열이_마지막이다() {
    assertThat(SettlementCsv.HEADER).hasSize(24);
    assertThat(SettlementCsv.HEADER.subList(20, 24))
        .containsExactly(
            "propertyCountNewMigrated",
            "customerCountNewMigrated",
            "agreedFeeUndatedCount",
            "asOf");
  }
}
