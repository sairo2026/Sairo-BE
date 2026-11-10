package com.sairo.be.domain.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.global.support.TimeRules;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CustomerLinkExpiryTest {

  private static final Instant ISSUED = Instant.parse("2026-12-01T01:00:00Z");

  @Test
  void 시작일이_멀면_발급_후_칠일이다() {
    LocalDate start = LocalDate.of(2027, 1, 20);

    assertThat(CustomerLinkExpiry.expiresAt(ISSUED, start))
        .isEqualTo(ISSUED.plus(Duration.ofDays(7)));
  }

  @Test
  void 시작일이_가까우면_첫_시간대_하루_전이다() {
    LocalDate start = LocalDate.of(2026, 12, 5);

    Instant expected = TimeRules.startOfDisplayDay(start).minus(Duration.ofHours(24));
    assertThat(CustomerLinkExpiry.expiresAt(ISSUED, start)).isEqualTo(expected);
  }

  @Test
  void 시작일이_이미_하루_이내이면_발급_후_여섯_시간이다() {
    assertThat(CustomerLinkExpiry.expiresAt(ISSUED, LocalDate.of(2026, 12, 2)))
        .isEqualTo(ISSUED.plus(Duration.ofHours(6)));
    assertThat(CustomerLinkExpiry.expiresAt(ISSUED, LocalDate.of(2026, 12, 1)))
        .isEqualTo(ISSUED.plus(Duration.ofHours(6)));
    assertThat(CustomerLinkExpiry.expiresAt(ISSUED, LocalDate.of(2026, 11, 20)))
        .isEqualTo(ISSUED.plus(Duration.ofHours(6)));
  }

  @Test
  void 정확히_하루_뒤_시작이어도_여섯_시간이다() {
    Instant issued =
        TimeRules.startOfDisplayDay(LocalDate.of(2026, 12, 3)).minus(Duration.ofHours(24));

    assertThat(CustomerLinkExpiry.expiresAt(issued, LocalDate.of(2026, 12, 3)))
        .isEqualTo(issued.plus(Duration.ofHours(6)));
  }
}
