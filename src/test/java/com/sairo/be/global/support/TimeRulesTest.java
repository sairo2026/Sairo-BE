package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TimeRulesTest {

  @Test
  void 저장은_UTC_표시는_서울_시각이다() {
    Instant instant = Instant.parse("2026-11-09T15:30:00Z");

    assertThat(TimeRules.toDisplay(instant).toString()).startsWith("2026-11-10T00:30");
    assertThat(TimeRules.displayDate(instant)).isEqualTo(LocalDate.of(2026, 11, 10));
    assertThat(TimeRules.STORAGE_ZONE.getId()).isEqualTo("Z");
  }

  @Test
  void 서울_하루의_시작과_끝은_UTC_전날_15시다() {
    LocalDate date = LocalDate.of(2026, 11, 10);

    assertThat(TimeRules.startOfDisplayDay(date)).isEqualTo(Instant.parse("2026-11-09T15:00:00Z"));
    assertThat(TimeRules.endOfDisplayDayExclusive(date))
        .isEqualTo(Instant.parse("2026-11-10T15:00:00Z"));
  }

  @Test
  void 시간_구간은_시작_포함_끝_제외로_겹침을_계산한다() {
    Instant nine = Instant.parse("2026-11-10T00:00:00Z");
    Instant ten = Instant.parse("2026-11-10T01:00:00Z");
    Instant eleven = Instant.parse("2026-11-10T02:00:00Z");

    assertThat(TimeRules.overlaps(nine, ten, ten, eleven)).isFalse();
    assertThat(TimeRules.overlaps(ten, eleven, nine, ten)).isFalse();
    assertThat(TimeRules.overlaps(nine, eleven, ten, eleven)).isTrue();
    assertThat(TimeRules.overlaps(nine, ten, nine, ten)).isTrue();
  }

  @Test
  void 날짜만_있는_만기는_해당_날짜의_서울_종료까지_유효하다() {
    LocalDate expiry = LocalDate.of(2026, 11, 10);

    assertThat(TimeRules.isDateOnlyExpired(expiry, Instant.parse("2026-11-10T14:59:59Z")))
        .isFalse();
    assertThat(TimeRules.isDateOnlyExpired(expiry, Instant.parse("2026-11-10T15:00:00Z"))).isTrue();
  }
}
