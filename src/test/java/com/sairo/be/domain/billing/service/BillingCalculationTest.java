package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.domain.billing.entity.SubscriptionPlan;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.mapper.BillingMapper;
import com.sairo.be.domain.billing.service.PriceCalculator.PriceTable;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BillingCalculationTest {

  private static final PriceTable TABLE = new PriceTable(39_900, 10_000, 8_000);

  @ParameterizedTest
  @CsvSource({
    "1, 39900",
    "2, 49900",
    "3, 59900",
    "4, 69900",
    "5, 77900",
    "6, 85900",
    "10, 117900",
    "20, 197900"
  })
  void 월_요금은_인원_구간별_산식으로_계산한다(int members, long expected) {
    assertThat(PriceCalculator.monthlyAmount(TABLE, members)).isEqualTo(expected);
  }

  @Test
  void 과금_인원이_1명_미만이면_거절한다() {
    assertThatThrownBy(() -> PriceCalculator.monthlyAmount(TABLE, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 요금제는_1명이면_SOLO이고_2명_이상이면_TEAM이다() {
    assertThat(PriceCalculator.planFor(1)).isEqualTo(SubscriptionPlan.SOLO);
    assertThat(PriceCalculator.planFor(2)).isEqualTo(SubscriptionPlan.TEAM);
    assertThat(PriceCalculator.planFor(30)).isEqualTo(SubscriptionPlan.TEAM);
  }

  @Test
  void 표시_요금제는_진행_중인_구독이면_과금_인원으로_계산하고_종료_정지_기업은_저장_값을_쓴다() {
    assertThat(PriceCalculator.displayPlan(SubscriptionStatus.ACTIVE, SubscriptionPlan.SOLO, 2))
        .isEqualTo(SubscriptionPlan.TEAM);
    assertThat(PriceCalculator.displayPlan(SubscriptionStatus.TRIAL, SubscriptionPlan.TEAM, 1))
        .isEqualTo(SubscriptionPlan.SOLO);
    assertThat(
            PriceCalculator.displayPlan(SubscriptionStatus.PRE_PAYMENT, SubscriptionPlan.SOLO, 3))
        .isEqualTo(SubscriptionPlan.TEAM);
    assertThat(PriceCalculator.displayPlan(SubscriptionStatus.ENDED, SubscriptionPlan.SOLO, 3))
        .isEqualTo(SubscriptionPlan.SOLO);
    assertThat(PriceCalculator.displayPlan(SubscriptionStatus.SUSPENDED, SubscriptionPlan.SOLO, 3))
        .isEqualTo(SubscriptionPlan.SOLO);
    assertThat(
            PriceCalculator.displayPlan(SubscriptionStatus.ACTIVE, SubscriptionPlan.ENTERPRISE, 3))
        .isEqualTo(SubscriptionPlan.ENTERPRISE);
  }

  @Test
  void 다인_상위_구간_기본_요금은_4명_요금이다() {
    assertThat(PriceCalculator.teamUpperTierBase(TABLE)).isEqualTo(69_900);
  }

  @Test
  void 세액은_총액에_세율을_곱해_분모에_세율을_더한_값으로_나눈_몫의_내림이다() {
    assertThat(VatCalculator.vatAmount(39_900, 1_000)).isEqualTo(3_627);
    assertThat(VatCalculator.vatAmount(59_900, 1_000)).isEqualTo(5_445);
    assertThat(VatCalculator.vatAmount(0, 1_000)).isZero();
    assertThat(VatCalculator.vatAmount(11_000, 1_000)).isEqualTo(1_000);
    assertThat(VatCalculator.vatAmount(39_900, 0)).isZero();
  }

  @Test
  void 회차_경계는_기준_시각의_KST_일자로_계산하고_없는_일자는_말일을_쓴다() {
    Instant anchor = Instant.parse("2027-01-31T09:00:00+09:00");
    assertThat(BillingPeriods.boundary(anchor, 0)).isEqualTo(anchor);
    assertThat(BillingPeriods.boundary(anchor, 1))
        .isEqualTo(Instant.parse("2027-02-28T09:00:00+09:00"));
    assertThat(BillingPeriods.boundary(anchor, 2))
        .isEqualTo(Instant.parse("2027-03-31T09:00:00+09:00"));
    assertThat(BillingPeriods.boundary(anchor, 3))
        .isEqualTo(Instant.parse("2027-04-30T09:00:00+09:00"));
  }

  @Test
  void 회차_경계는_UTC_날짜가_아니라_KST_날짜를_따른다() {
    Instant anchor = Instant.parse("2027-07-01T00:00:00+09:00");
    assertThat(BillingPeriods.boundary(anchor, 1))
        .isEqualTo(Instant.parse("2027-08-01T00:00:00+09:00"));
  }

  @ParameterizedTest
  @CsvSource({
    "2027-01-01T14:30:00+09:00, 2027-02-01T14:30:00+09:00",
    "2027-01-15T00:00:00+09:00, 2027-02-15T00:00:00+09:00",
    "2027-01-30T14:30:00+09:00, 2027-02-28T14:30:00+09:00",
    "2027-01-31T14:30:00+09:00, 2027-02-28T14:30:00+09:00",
    "2028-02-29T14:30:00+09:00, 2028-03-29T14:30:00+09:00",
    "2027-03-31T14:30:00+09:00, 2027-04-30T14:30:00+09:00",
    "2027-12-31T14:30:00+09:00, 2028-01-31T14:30:00+09:00"
  })
  void 월별_청구_경계는_KST_기준_날짜와_시각을_유지한다(String start, String expected) {
    assertThat(BillingPeriods.boundary(Instant.parse(start), 1))
        .isEqualTo(Instant.parse(expected));
  }

  @Test
  void 월말_보정은_원래_청구일로_복귀하고_100년_이후에도_기준일을_잃지_않는다() {
    Instant anchor = Instant.parse("2027-01-31T14:30:00+09:00");
    assertThat(BillingJobService.nextBoundary(anchor, Instant.parse("2027-02-28T14:30:00+09:00")))
        .isEqualTo(Instant.parse("2027-03-31T14:30:00+09:00"));
    assertThat(BillingJobService.nextBoundary(anchor, Instant.parse("2128-02-29T14:30:00+09:00")))
        .isEqualTo(Instant.parse("2128-03-31T14:30:00+09:00"));
  }

  @Test
  void 스케줄러가_지연되어도_24회_갱신은_원래_월말_기준일을_유지한다() {
    Instant anchor = Instant.parse("2027-01-31T14:30:00+09:00");
    Instant previous = anchor;
    for (int month = 1; month <= 24; month++) {
      Instant expected = BillingPeriods.boundary(anchor, month);
      assertThat(BillingJobService.nextBoundary(anchor, previous)).isEqualTo(expected);
      previous = expected;
    }
  }

  @Test
  void 윤일_기준일은_평년에는_말일이고_다음_윤년에는_29일로_복귀한다() {
    Instant anchor = Instant.parse("2028-02-29T00:00:00+09:00");
    assertThat(BillingPeriods.boundary(anchor, 12))
        .isEqualTo(Instant.parse("2029-02-28T00:00:00+09:00"));
    assertThat(BillingPeriods.boundary(anchor, 48))
        .isEqualTo(Instant.parse("2032-02-29T00:00:00+09:00"));
  }

  @Test
  void PG가_지원하지_않는_0원과_최소금액_미만은_승인_요청_전에_차단한다() {
    for (long amount : new long[] {0, 99}) {
      assertThatThrownBy(() -> BillingChargeService.requireSupportedPgAmount(amount))
          .isInstanceOf(BusinessException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.BILLING_AMOUNT_UNSUPPORTED);
    }
    BillingChargeService.requireSupportedPgAmount(100);
  }

  @Test
  void 반환기간은_종료_시각_이상_30일_미만이다() {
    Instant ended = Instant.parse("2027-01-01T00:00:00Z");
    assertThat(BillingPeriods.isInReturnPeriod(ended, ended)).isTrue();
    assertThat(BillingPeriods.isInReturnPeriod(ended, ended.plusSeconds(86_400L * 30 - 1)))
        .isTrue();
    assertThat(BillingPeriods.isInReturnPeriod(ended, ended.plusSeconds(86_400L * 30))).isFalse();
    assertThat(BillingPeriods.isInReturnPeriod(null, ended)).isFalse();
  }

  @Test
  void 카드_끝_네_자리는_마스킹된_번호의_마지막_숫자_네_개다() {
    assertThat(BillingKeyService.lastFourDigits("1234-****-****-5678")).isEqualTo("5678");
    assertThat(BillingKeyService.lastFourDigits("43301234****1234")).isEqualTo("1234");
    assertThat(BillingKeyService.lastFourDigits("***")).isNull();
    assertThat(BillingKeyService.lastFourDigits(null)).isNull();
  }

  @Test
  void 이메일은_앞_두_글자만_남기고_가린다() {
    assertThat(BillingMapper.maskEmail("office@example.com")).isEqualTo("of***@example.com");
    assertThat(BillingMapper.maskEmail("a@example.com")).isEqualTo("a***@example.com");
    assertThat(BillingMapper.maskEmail("invalid")).isEqualTo("***");
    assertThat(BillingMapper.maskEmail(null)).isNull();
  }
}
