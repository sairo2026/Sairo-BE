package com.sairo.be.domain.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.visit.entity.GroupingPreference;
import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Visit;
import com.sairo.be.domain.visit.service.ConditionSpec.Window;
import com.sairo.be.domain.visit.service.ConditionSpec.WindowType;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Subject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisitRequestCheckerTest {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
  private static final Instant NOW = LocalDate.of(2026, 11, 10).atStartOfDay(KST).toInstant();

  private static Instant at(int hour, int minute) {
    return DAY.atTime(hour, minute).atZone(KST).toInstant();
  }

  private static ConditionSpec spec(Integer max, Integer gap, Integer lead, Integer single) {
    List<Window> windows = new ArrayList<>();
    for (int weekday = 1; weekday <= 7; weekday++) {
      windows.add(new Window(WindowType.WEEKDAY, weekday, null, 9 * 60, 18 * 60, false));
    }
    return new ConditionSpec(
        NOW.minusSeconds(86_400),
        NOW.plusSeconds(60L * 86_400),
        max,
        gap,
        lead,
        single,
        GroupingPreference.CONSECUTIVE,
        windows,
        List.of());
  }

  private static Subject subject(int fromHour, int fromMinute, int toHour, int toMinute) {
    return new Subject(
        at(fromHour, fromMinute),
        at(toHour, toMinute),
        1L,
        10L,
        NOW.minusSeconds(86_400),
        NOW.plusSeconds(30L * 86_400));
  }

  private static List<Slot> customerSlot(int fromHour, int toHour) {
    return List.of(new Slot(at(fromHour, 0), at(toHour, 0)));
  }

  private static Visit visit(long id, int fromHour, int toHour) {
    return new Visit(id, 2L, at(fromHour, 0), at(toHour, 0), "SCHEDULED", id, 10L);
  }

  @Test
  void 모든_조건을_지키면_위반이_없다() {
    List<String> rules =
        VisitRequestChecker.violatedRules(
            spec(3, 30, 60, 60), subject(10, 0, 10, 30), List.of(), customerSlot(9, 17), NOW);

    assertThat(rules).isEmpty();
  }

  @Test
  void 고객_가능_시간_밖이면_고객_충돌이고_맞닿은_구간은_합쳐서_본다() {
    ConditionSpec spec = spec(null, null, null, null);
    List<Slot> adjacent = List.of(new Slot(at(9, 0), at(11, 0)), new Slot(at(11, 0), at(13, 0)));

    assertThat(
            VisitRequestChecker.violatedRules(
                spec, subject(10, 30, 11, 30), List.of(), adjacent, NOW))
        .isEmpty();
    assertThat(
            VisitRequestChecker.violatedRules(
                spec, subject(12, 30, 13, 30), List.of(), adjacent, NOW))
        .containsExactly("CUSTOMER_CONFLICT");
    assertThat(
            VisitRequestChecker.violatedRules(
                spec, subject(10, 0, 10, 30), List.of(), List.of(), NOW))
        .containsExactly("CUSTOMER_CONFLICT");
  }

  @Test
  void 승인자_가능_시간대와_한_번_방문_상한을_검사한다() {
    List<String> rules =
        VisitRequestChecker.violatedRules(
            spec(null, null, null, 20),
            subject(17, 30, 18, 30),
            List.of(),
            customerSlot(9, 20),
            NOW);

    assertThat(rules).containsExactly("WINDOW_MISMATCH", "SINGLE_VISIT_MAX");
  }

  @Test
  void 최소_사전_신청_시간은_신청_시각_기준이다() {
    ConditionSpec spec = spec(null, null, 20 * 24 * 60, null);

    assertThat(
            VisitRequestChecker.violatedRules(
                spec, subject(10, 0, 10, 30), List.of(), customerSlot(9, 17), NOW))
        .containsExactly("MIN_LEAD");
  }

  @Test
  void 확정_방문과_겹치면_매물_충돌이다() {
    List<String> rules =
        VisitRequestChecker.violatedRules(
            spec(null, null, null, null),
            subject(10, 0, 10, 30),
            List.of(visit(1, 10, 11)),
            customerSlot(9, 17),
            NOW);

    assertThat(rules).contains("PROPERTY_CONFLICT");
  }

  @Test
  void 새_신청_때문에_생기는_하루_횟수와_간격_위반만_센다() {
    ConditionSpec spec = spec(1, 60, null, null);
    List<Visit> existing = List.of(visit(1, 14, 15));

    assertThat(
            VisitRequestChecker.violatedRules(
                spec, subject(10, 0, 10, 30), existing, customerSlot(9, 17), NOW))
        .containsExactly("MAX_VISITS_PER_DAY");
    assertThat(
            VisitRequestChecker.violatedRules(
                spec(5, 60, null, null),
                subject(13, 30, 14, 0),
                existing,
                customerSlot(9, 17),
                NOW))
        .contains("MIN_GAP");
  }

  @Test
  void 이미_위반_중이던_확정_방문은_새_신청의_책임이_아니다() {
    List<Visit> existing = List.of(visit(1, 10, 11), visit(2, 11, 12));

    assertThat(
            VisitRequestChecker.violatedRules(
                spec(null, 60, null, null),
                subject(16, 0, 16, 30),
                existing,
                customerSlot(9, 17),
                NOW))
        .isEmpty();
  }

  @Test
  void 출입승인_유효_기간_밖이면_권한_없음이다() {
    Subject outside =
        new Subject(at(10, 0), at(10, 30), 1L, 10L, at(11, 0), NOW.plusSeconds(30L * 86_400));

    assertThat(
            VisitRequestChecker.violatedRules(
                spec(null, null, null, null), outside, List.of(), customerSlot(9, 17), NOW))
        .containsExactly("NO_PERMISSION");
  }
}
