package com.sairo.be.domain.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.visit.repository.ScheduleBusyRepository.BusyVisit;
import com.sairo.be.domain.visit.service.VisitFeasibility.Hit;
import com.sairo.be.domain.visit.service.VisitFeasibility.HitKind;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisitFeasibilityTest {

  private static Instant t(String hhmm) {
    return Instant.parse("2030-01-01T" + hhmm + ":00Z");
  }

  private static BusyVisit busy(String from, String to, String travelEnd) {
    return new BusyVisit(
        1L,
        2L,
        3L,
        4L,
        5L,
        t(from),
        t(to),
        travelEnd == null ? null : t(to),
        travelEnd == null ? null : t(travelEnd));
  }

  @Test
  void 담당자_방문과_겹치면_중개사_충돌이다() {
    List<Hit> hits =
        VisitFeasibility.busyHits(
            t("10:00"), t("10:30"), 0, List.of(busy("10:20", "10:50", null)), List.of(), List.of());

    assertThat(hits)
        .singleElement()
        .satisfies(
            hit -> {
              assertThat(hit.rule()).isEqualTo("BROKER_CONFLICT");
              assertThat(hit.kind()).isEqualTo(HitKind.MANAGER_OVERLAP);
            });
  }

  @Test
  void 담당자_이동_구간과_겹치면_이동_시간_충돌이다() {
    List<Hit> hits =
        VisitFeasibility.busyHits(
            t("10:00"),
            t("10:30"),
            0,
            List.of(busy("09:20", "09:50", "10:10")),
            List.of(),
            List.of());

    assertThat(hits).singleElement().extracting(Hit::rule).isEqualTo("TRAVEL_CONFLICT");
  }

  @Test
  void 새_방문_뒤_이동_구간이_다음_확정_방문과_겹쳐도_이동_시간_충돌이다() {
    List<Hit> hits =
        VisitFeasibility.busyHits(
            t("10:00"),
            t("10:30"),
            20,
            List.of(busy("10:40", "11:10", null)),
            List.of(),
            List.of());

    assertThat(hits).singleElement().extracting(Hit::rule).isEqualTo("TRAVEL_CONFLICT");
  }

  @Test
  void 이동_구간_뒤에_시작하는_방문은_충돌이_아니다() {
    List<Hit> hits =
        VisitFeasibility.busyHits(
            t("10:10"),
            t("10:40"),
            0,
            List.of(busy("09:20", "09:50", "10:10")),
            List.of(busy("09:20", "09:50", "10:10")),
            List.of(busy("09:00", "10:10", null)));

    assertThat(hits).isEmpty();
  }

  @Test
  void 고객과_매물_충돌은_각각_고객_일정_매물_일정_충돌이다() {
    List<Hit> hits =
        VisitFeasibility.busyHits(
            t("10:00"),
            t("10:30"),
            0,
            List.of(),
            List.of(busy("10:00", "10:30", null)),
            List.of(busy("10:10", "10:20", null)));

    assertThat(hits)
        .extracting(Hit::rule)
        .containsExactly("CUSTOMER_CONFLICT", "PROPERTY_CONFLICT");
    assertThat(hits)
        .extracting(Hit::kind)
        .containsExactly(HitKind.CUSTOMER_OVERLAP, HitKind.PROPERTY_OVERLAP);
  }

  @Test
  void 슬롯_계산은_5분_격자로_올리고_맞닿은_구간을_합친다() {
    assertThat(ScheduleSlotCalculator.ceil(Instant.parse("2030-01-01T10:01:00Z")))
        .isEqualTo(Instant.parse("2030-01-01T10:05:00Z"));
    assertThat(ScheduleSlotCalculator.ceil(Instant.parse("2030-01-01T10:05:00Z")))
        .isEqualTo(Instant.parse("2030-01-01T10:05:00Z"));
    assertThat(
            ScheduleSlotCalculator.mergedSlots(
                List.of(
                    new Slot(t("12:00"), t("13:00")),
                    new Slot(t("10:00"), t("11:00")),
                    new Slot(t("11:00"), t("11:30")))))
        .containsExactly(new Slot(t("10:00"), t("11:30")), new Slot(t("12:00"), t("13:00")));
  }

  @Test
  void 신뢰_구간은_예_비율_기준이고_잠금_키는_사무소와_자원_종류를_결합한다() {
    assertThat(TrustSummary.bandOf(9, 10)).isEqualTo("HIGH");
    assertThat(TrustSummary.bandOf(7, 10)).isEqualTo("MID");
    assertThat(TrustSummary.bandOf(69, 100)).isEqualTo("LOW");
    assertThat(ScheduleLockKeys.manager(1L, 2L)).isEqualTo("visit:1:manager:2");
    assertThat(ScheduleLockKeys.customer(1L, 3L)).isEqualTo("visit:1:customer:3");
    assertThat(ScheduleLockKeys.property(4L, 5L)).isEqualTo("visit:4:property:5");
    assertThat(ApprovalAccess.lockKey(9L)).isEqualTo("visit:approval:9");
  }
}
