package com.sairo.be.domain.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApproverLinkReviewExpiryTest {

  private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

  @Test
  void 평가_대기_방문이_없으면_발급_후_7일과_유효_종료_중_빠른_시각이다() {
    Instant validUntil = NOW.plus(Duration.ofDays(2));
    assertThat(ApproverPropertyLinkService.expiresAt(NOW, validUntil, Optional.empty()))
        .isEqualTo(validUntil);
    assertThat(
            ApproverPropertyLinkService.expiresAt(
                NOW, NOW.plus(Duration.ofDays(30)), Optional.empty()))
        .isEqualTo(NOW.plus(Duration.ofDays(7)));
  }

  @Test
  void 평가_대기_방문이_있으면_가장_늦은_평가_기한까지_늘리되_7일을_넘지_않는다() {
    Instant deadline = NOW.plus(Duration.ofDays(3));
    assertThat(
            ApproverPropertyLinkService.expiresAt(
                NOW, NOW.minus(Duration.ofDays(1)), Optional.of(deadline)))
        .isEqualTo(deadline);
    assertThat(
            ApproverPropertyLinkService.expiresAt(
                NOW, NOW.plus(Duration.ofDays(1)), Optional.of(NOW.plus(Duration.ofDays(12)))))
        .isEqualTo(NOW.plus(Duration.ofDays(7)));
    assertThat(
            ApproverPropertyLinkService.expiresAt(
                NOW, NOW.plus(Duration.ofDays(5)), Optional.of(deadline)))
        .isEqualTo(NOW.plus(Duration.ofDays(5)));
  }
}
