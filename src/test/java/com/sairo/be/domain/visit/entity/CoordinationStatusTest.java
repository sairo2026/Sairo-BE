package com.sairo.be.domain.visit.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CoordinationStatusTest {

  private static VisitCoordination open() {
    return VisitCoordination.open(
        1L, 2L, 3L, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 10));
  }

  @Test
  void 조율_상태는_아홉_종이고_신청_진행이_포함된다() {
    assertThat(CoordinationStatus.values())
        .extracting(Enum::name)
        .containsExactly(
            "TIME_COLLECTING",
            "COORDINATABLE",
            "FINAL_CONFIRMATION_REQUIRED",
            "APPLYING",
            "CONFIRMED",
            "RESCHEDULE_REQUIRED",
            "VISIT_COMPLETED",
            "VISIT_INCOMPLETE",
            "CANCELLED");
  }

  @Test
  void 종료_상태에서는_어디로도_전이할_수_없다() {
    for (CoordinationStatus terminal :
        new CoordinationStatus[] {
          CoordinationStatus.VISIT_COMPLETED,
          CoordinationStatus.VISIT_INCOMPLETE,
          CoordinationStatus.CANCELLED
        }) {
      for (CoordinationStatus next : CoordinationStatus.values()) {
        assertThat(terminal.canMoveTo(next)).as(terminal + " to " + next).isFalse();
      }
    }
  }

  @Test
  void 정합화_후_확정_경로는_신청_진행을_거친다() {
    assertThat(
            CoordinationStatus.FINAL_CONFIRMATION_REQUIRED.canMoveTo(CoordinationStatus.CONFIRMED))
        .isFalse();
    assertThat(
            CoordinationStatus.FINAL_CONFIRMATION_REQUIRED.canMoveTo(CoordinationStatus.APPLYING))
        .isTrue();
    assertThat(CoordinationStatus.APPLYING.canMoveTo(CoordinationStatus.CONFIRMED)).isTrue();
    assertThat(
            CoordinationStatus.APPLYING.canMoveTo(CoordinationStatus.FINAL_CONFIRMATION_REQUIRED))
        .isTrue();
    assertThat(CoordinationStatus.COORDINATABLE.canMoveTo(CoordinationStatus.CONFIRMED)).isFalse();
    assertThat(CoordinationStatus.TIME_COLLECTING.canMoveTo(CoordinationStatus.CONFIRMED))
        .isFalse();
  }

  @Test
  void 전이하면_이력_객체를_함께_돌려준다() {
    VisitCoordination coordination = open();
    Instant at = Instant.parse("2026-12-01T00:00:00Z");

    VisitCoordinationStatusHistory history =
        coordination.moveTo(
            CoordinationStatus.COORDINATABLE, StatusChangeSource.CUSTOMER, null, null, at);

    assertThat(coordination.getStatus()).isEqualTo(CoordinationStatus.COORDINATABLE);
    assertThat(history.getFromStatus()).isEqualTo(CoordinationStatus.TIME_COLLECTING);
    assertThat(history.getToStatus()).isEqualTo(CoordinationStatus.COORDINATABLE);
    assertThat(history.getSource()).isEqualTo(StatusChangeSource.CUSTOMER);
    assertThat(history.getEffectiveAt()).isEqualTo(at);
  }

  @Test
  void 허용되지_않는_전이는_상태를_바꾸지_않고_거절한다() {
    VisitCoordination coordination = open();

    assertThatThrownBy(
            () ->
                coordination.moveTo(
                    CoordinationStatus.CONFIRMED,
                    StatusChangeSource.STAFF,
                    1L,
                    null,
                    Instant.now()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(coordination.getStatus()).isEqualTo(CoordinationStatus.TIME_COLLECTING);
  }

  @Test
  void 취소_전이는_취소_시각과_사유를_함께_채운다() {
    VisitCoordination coordination = open();
    Instant at = Instant.parse("2026-12-02T00:00:00Z");

    coordination.moveTo(CoordinationStatus.CANCELLED, StatusChangeSource.STAFF, 1L, "고객 요청", at);

    assertThat(coordination.getCancelledAt()).isEqualTo(at);
    assertThat(coordination.getCancelReason()).isEqualTo("고객 요청");
  }

  @Test
  void 재조율_필요_전이는_일반_전이_메서드로_할_수_없다() {
    VisitCoordination coordination = open();
    coordination.moveTo(
        CoordinationStatus.COORDINATABLE, StatusChangeSource.CUSTOMER, null, null, Instant.now());

    assertThatThrownBy(
            () ->
                coordination.moveTo(
                    CoordinationStatus.RESCHEDULE_REQUIRED,
                    StatusChangeSource.STAFF,
                    1L,
                    null,
                    Instant.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 고객_응답과_후보_변경이_허용되는_상태를_구분한다() {
    assertThat(CoordinationStatus.TIME_COLLECTING.acceptsCustomerResponse()).isTrue();
    assertThat(CoordinationStatus.RESCHEDULE_REQUIRED.acceptsCustomerResponse()).isTrue();
    assertThat(CoordinationStatus.APPLYING.acceptsCustomerResponse()).isFalse();
    assertThat(CoordinationStatus.CONFIRMED.acceptsCustomerResponse()).isFalse();
    assertThat(CoordinationStatus.TIME_COLLECTING.acceptsCandidateRemoval()).isTrue();
    assertThat(CoordinationStatus.COORDINATABLE.acceptsCandidateRemoval()).isFalse();
    assertThat(CoordinationStatus.CANCELLED.acceptsCandidateAddition()).isFalse();
  }
}
