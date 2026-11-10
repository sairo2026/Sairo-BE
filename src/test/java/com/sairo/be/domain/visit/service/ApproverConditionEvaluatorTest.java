package com.sairo.be.domain.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Violation;
import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Visit;
import com.sairo.be.domain.visit.service.ConditionSpec.DateRule;
import com.sairo.be.domain.visit.service.ConditionSpec.Limit;
import com.sairo.be.domain.visit.service.ConditionSpec.LimitKind;
import com.sairo.be.domain.visit.service.ConditionSpec.Window;
import com.sairo.be.domain.visit.service.ConditionSpec.WindowType;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApproverConditionEvaluatorTest {

  private static final LocalDate MONDAY = LocalDate.of(2030, 1, 7);
  private static final Instant NOW = TimeRules.startOfDisplayDay(MONDAY.minusDays(10));

  private static Instant at(LocalDate day, int hour, int minute) {
    return TimeRules.startOfDisplayDay(day).plusSeconds(hour * 3600L + minute * 60L);
  }

  private static Visit visit(long id, LocalDate day, int startHour, int startMinute, int minutes) {
    Instant start = at(day, startHour, startMinute);
    return new Visit(id, 1L, start, start.plusSeconds(minutes * 60L), "SCHEDULED", id, 1L);
  }

  private static Window weekday(int weekday, int from, int to, boolean exception) {
    return new Window(WindowType.WEEKDAY, weekday, null, from * 60, to * 60, exception);
  }

  private static Window date(LocalDate day, int from, int to, boolean exception) {
    return new Window(WindowType.DATE, null, day, from * 60, to * 60, exception);
  }

  private static ConditionSpec spec(
      Integer max, Integer gap, Integer single, List<Window> windows, List<DateRule> rules) {
    return new ConditionSpec(
        TimeRules.startOfDisplayDay(MONDAY.minusDays(30)),
        TimeRules.startOfDisplayDay(MONDAY.plusDays(60)),
        max,
        gap,
        null,
        single,
        null,
        windows,
        rules);
  }

  private static List<String> rulesOf(List<Violation> violations, long visitId) {
    return violations.stream()
        .filter(v -> v.visit().scheduleItemId() == visitId)
        .findFirst()
        .map(Violation::rules)
        .orElse(List.of());
  }

  @Test
  void 시간대_안에_완전히_들어가면_위반이_없고_끝_시각은_포함하지_않는다() {
    ConditionSpec spec = spec(null, null, null, List.of(weekday(1, 9, 18, false)), List.of());

    assertThat(ApproverConditionEvaluator.evaluate(spec, List.of(visit(1, MONDAY, 17, 0, 60)), NOW))
        .isEmpty();
    assertThat(
            rulesOf(
                ApproverConditionEvaluator.evaluate(
                    spec, List.of(visit(2, MONDAY, 17, 30, 60)), NOW),
                2))
        .containsExactly(ApproverConditionEvaluator.WINDOW_MISMATCH);
  }

  @Test
  void 제외_구간이_가능_구간보다_우선하고_날짜_행이_있으면_요일_행을_쓰지_않는다() {
    ConditionSpec withException =
        spec(
            null,
            null,
            null,
            List.of(weekday(1, 9, 18, false), weekday(1, 12, 13, true)),
            List.of());
    assertThat(
            ApproverConditionEvaluator.evaluate(
                withException, List.of(visit(1, MONDAY, 12, 0, 30)), NOW))
        .hasSize(1);
    assertThat(
            ApproverConditionEvaluator.evaluate(
                withException, List.of(visit(2, MONDAY, 10, 0, 60)), NOW))
        .isEmpty();

    ConditionSpec dateOverride =
        spec(
            null,
            null,
            null,
            List.of(weekday(1, 9, 18, false), date(MONDAY, 14, 15, false)),
            List.of());
    assertThat(
            ApproverConditionEvaluator.evaluate(
                dateOverride, List.of(visit(3, MONDAY, 10, 0, 60)), NOW))
        .hasSize(1);
    assertThat(
            ApproverConditionEvaluator.evaluate(
                dateOverride, List.of(visit(4, MONDAY, 14, 0, 60)), NOW))
        .isEmpty();

    ConditionSpec closedDay =
        spec(
            null,
            null,
            null,
            List.of(weekday(1, 9, 18, false), date(MONDAY, 0, 24, true)),
            List.of());
    assertThat(
            ApproverConditionEvaluator.evaluate(
                closedDay, List.of(visit(5, MONDAY, 10, 0, 60)), NOW))
        .hasSize(1);
  }

  @Test
  void 자정을_넘는_방문은_양쪽_날짜의_시간대에_모두_들어가야_한다() {
    ConditionSpec spec =
        spec(
            null,
            null,
            null,
            List.of(weekday(1, 22, 24, false), weekday(2, 0, 2, false)),
            List.of());

    assertThat(
            ApproverConditionEvaluator.evaluate(spec, List.of(visit(1, MONDAY, 23, 0, 120)), NOW))
        .isEmpty();
    assertThat(
            ApproverConditionEvaluator.evaluate(spec, List.of(visit(2, MONDAY, 23, 0, 190)), NOW))
        .hasSize(1);
  }

  @Test
  void 하루_최대_횟수는_그날의_규칙으로_세고_초과분만_위반이다() {
    List<Visit> visits =
        List.of(
            visit(1, MONDAY, 9, 0, 30), visit(2, MONDAY, 11, 0, 30), visit(3, MONDAY, 14, 0, 30));
    List<Window> windows = List.of(weekday(1, 0, 24, false));

    List<Violation> byDefault =
        ApproverConditionEvaluator.evaluate(spec(2, null, null, windows, List.of()), visits, NOW);
    assertThat(byDefault).hasSize(1);
    assertThat(rulesOf(byDefault, 3))
        .containsExactly(ApproverConditionEvaluator.MAX_VISITS_PER_DAY);

    DateRule unlimited =
        new DateRule(MONDAY, new Limit(LimitKind.UNLIMITED, null), null, null, null);
    assertThat(
            ApproverConditionEvaluator.evaluate(
                spec(2, null, null, windows, List.of(unlimited)), visits, NOW))
        .isEmpty();
    DateRule strict = new DateRule(MONDAY, new Limit(LimitKind.VALUE, 1), null, null, null);
    assertThat(
            ApproverConditionEvaluator.evaluate(
                spec(5, null, null, windows, List.of(strict)), visits, NOW))
        .hasSize(2);
  }

  @Test
  void 최소_간격은_뒤_방문이_위반이고_날짜가_다르면_두_날짜_중_큰_값을_요구한다() {
    List<Window> windows = List.of(weekday(1, 0, 24, false), weekday(2, 0, 24, false));
    List<Visit> sameDay = List.of(visit(1, MONDAY, 9, 0, 30), visit(2, MONDAY, 9, 40, 30));

    List<Violation> tight =
        ApproverConditionEvaluator.evaluate(spec(null, 30, null, windows, List.of()), sameDay, NOW);
    assertThat(tight).hasSize(1);
    assertThat(rulesOf(tight, 2)).containsExactly(ApproverConditionEvaluator.MIN_GAP);
    assertThat(
            ApproverConditionEvaluator.evaluate(
                spec(null, 10, null, windows, List.of()), sameDay, NOW))
        .isEmpty();

    LocalDate tuesday = MONDAY.plusDays(1);
    List<Visit> acrossDays = List.of(visit(3, MONDAY, 23, 30, 25), visit(4, tuesday, 0, 20, 30));
    DateRule mondayStrict = new DateRule(MONDAY, null, new Limit(LimitKind.VALUE, 60), null, null);
    assertThat(
            rulesOf(
                ApproverConditionEvaluator.evaluate(
                    spec(null, 10, null, windows, List.of(mondayStrict)), acrossDays, NOW),
                4))
        .containsExactly(ApproverConditionEvaluator.MIN_GAP);
    assertThat(
            ApproverConditionEvaluator.evaluate(
                spec(null, 10, null, windows, List.of()), acrossDays, NOW))
        .isEmpty();
  }

  @Test
  void 일회_허용_시간과_적용_기간은_방문_시작_기준으로_판정한다() {
    List<Window> windows = List.of(weekday(1, 0, 24, false));
    DateRule longDay = new DateRule(MONDAY, null, null, null, new Limit(LimitKind.UNLIMITED, null));

    assertThat(
            rulesOf(
                ApproverConditionEvaluator.evaluate(
                    spec(null, null, 30, windows, List.of()),
                    List.of(visit(1, MONDAY, 9, 0, 45)),
                    NOW),
                1))
        .containsExactly(ApproverConditionEvaluator.SINGLE_VISIT_MAX);
    assertThat(
            ApproverConditionEvaluator.evaluate(
                spec(null, null, 30, windows, List.of(longDay)),
                List.of(visit(2, MONDAY, 9, 0, 45)),
                NOW))
        .isEmpty();

    ConditionSpec ended =
        new ConditionSpec(
            TimeRules.startOfDisplayDay(MONDAY.minusDays(30)),
            TimeRules.startOfDisplayDay(MONDAY),
            null,
            null,
            null,
            null,
            null,
            windows,
            List.of());
    assertThat(
            rulesOf(
                ApproverConditionEvaluator.evaluate(
                    ended, List.of(visit(3, MONDAY, 0, 0, 30)), NOW),
                3))
        .containsExactly(ApproverConditionEvaluator.CONDITION_EXPIRED);
  }

  @Test
  void 이미_끝났거나_예정이_아닌_방문은_영향_대상이_아니고_이웃으로만_센다() {
    List<Window> windows = List.of(weekday(1, 0, 24, false));
    Visit done = new Visit(1L, 1L, at(MONDAY, 9, 0), at(MONDAY, 9, 30), "COMPLETED", 1L, 1L);
    Visit next = visit(2, MONDAY, 9, 40, 30);
    List<Visit> visits = new ArrayList<>(List.of(done, next));

    List<Violation> result =
        ApproverConditionEvaluator.evaluate(spec(null, 30, null, windows, List.of()), visits, NOW);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).visit().scheduleItemId()).isEqualTo(2L);
    assertThat(
            ApproverConditionEvaluator.evaluate(
                spec(null, 30, null, List.of(), List.of()), List.of(done), NOW))
        .isEmpty();
    assertThat(
            ApproverConditionEvaluator.evaluate(
                spec(null, 30, null, List.of(), List.of()),
                List.of(visit(3, MONDAY, 9, 0, 30)),
                at(MONDAY, 12, 0)))
        .isEmpty();
  }
}
