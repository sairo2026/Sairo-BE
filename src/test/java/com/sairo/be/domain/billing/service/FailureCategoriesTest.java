package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.billing.entity.FailureCategory;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FailureCategoriesTest {

  @ParameterizedTest
  @CsvSource({
    "EXPIRED_CARD, CARD_UNUSABLE",
    "INVALID_STOPPED_CARD, CARD_UNUSABLE",
    "NOT_SUPPORTED_CARD_TYPE, CARD_UNUSABLE",
    "EXCEED_MAX_AMOUNT, INSUFFICIENT_FUNDS",
    "NOT_ENOUGH_BALANCE, INSUFFICIENT_FUNDS",
    "PROVIDER_TIMEOUT, TIMEOUT",
    "STUB_DECLINED, OTHER",
    "UNKNOWN_ERROR, OTHER"
  })
  void 결제대행사_오류_코드를_실패_분류로_바꾼다(String code, FailureCategory expected) {
    assertThat(FailureCategories.from(code)).isEqualTo(expected);
  }

  @Test
  void 오류_코드가_없으면_기타로_분류한다() {
    assertThat(FailureCategories.from(null)).isEqualTo(FailureCategory.OTHER);
    assertThat(FailureCategories.from(" ")).isEqualTo(FailureCategory.OTHER);
  }

  @Test
  void 자동_재시도는_D0_1시간_3일_7일이고_네_번째_실패_뒤에는_없다() {
    Instant d0 = Instant.parse("2027-01-01T00:00:00Z");
    assertThat(FailureCategories.nextRetryAt(d0, 1, FailureCategory.OTHER))
        .isEqualTo(d0.plus(Duration.ofHours(1)));
    assertThat(FailureCategories.nextRetryAt(d0, 2, FailureCategory.INSUFFICIENT_FUNDS))
        .isEqualTo(d0.plus(Duration.ofDays(3)));
    assertThat(FailureCategories.nextRetryAt(d0, 3, FailureCategory.TIMEOUT))
        .isEqualTo(d0.plus(Duration.ofDays(7)));
    assertThat(FailureCategories.nextRetryAt(d0, 4, FailureCategory.OTHER)).isNull();
    assertThat(FailureCategories.nextRetryAt(d0, 0, FailureCategory.OTHER)).isNull();
  }

  @Test
  void 명확한_사용불가_카드는_자동_재시도를_멈춘다() {
    assertThat(
            FailureCategories.nextRetryAt(
                Instant.parse("2027-01-01T00:00:00Z"), 1, FailureCategory.CARD_UNUSABLE))
        .isNull();
  }

  @Test
  void 다음_회차_경계는_기준일_원래_일자를_기준으로_계산한다() {
    Instant anchor = Instant.parse("2027-01-31T09:00:00+09:00");
    assertThat(BillingJobService.nextBoundary(anchor, anchor))
        .isEqualTo(Instant.parse("2027-02-28T09:00:00+09:00"));
    assertThat(BillingJobService.nextBoundary(anchor, Instant.parse("2027-02-28T09:00:00+09:00")))
        .isEqualTo(Instant.parse("2027-03-31T09:00:00+09:00"));
    assertThat(BillingJobService.nextBoundary(null, anchor))
        .isEqualTo(Instant.parse("2027-02-28T09:00:00+09:00"));
  }
}
