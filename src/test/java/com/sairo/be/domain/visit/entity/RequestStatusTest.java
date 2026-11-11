package com.sairo.be.domain.visit.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RequestStatusTest {

  private static VisitRequest pending() {
    Instant now = Instant.parse("2026-11-10T00:00:00Z");
    return VisitRequest.submitNew(
        1L,
        2L,
        3L,
        4L,
        null,
        4L,
        5L,
        6L,
        7L,
        now.plusSeconds(3600),
        now.plusSeconds(5400),
        30,
        null,
        null,
        now);
  }

  @Test
  void 여덟_가지_상태가_있고_신청_대기와_선택됨만_진행_중이다() {
    assertThat(RequestStatus.values()).hasSize(8);
    assertThat(RequestStatus.ACTIVE)
        .containsExactlyInAnyOrder(RequestStatus.PENDING, RequestStatus.SELECTED);
    assertThat(RequestStatus.PENDING.isActive()).isTrue();
    assertThat(RequestStatus.CONFIRMED.isActive()).isFalse();
  }

  @Test
  void 종결_상태는_다섯_가지이고_나가는_전이가_없다() {
    for (RequestStatus status :
        new RequestStatus[] {
          RequestStatus.REVALIDATION_FAILED,
          RequestStatus.TIME_CLOSED,
          RequestStatus.EXPIRED,
          RequestStatus.WITHDRAWN,
          RequestStatus.CANCELLED
        }) {
      assertThat(status.isClosed()).isTrue();
      for (RequestStatus next : RequestStatus.values()) {
        assertThat(status.canMoveTo(next)).isFalse();
      }
    }
    assertThat(RequestStatus.CONFIRMED.isClosed()).isFalse();
    assertThat(RequestStatus.CONFIRMED.canMoveTo(RequestStatus.CANCELLED)).isTrue();
  }

  @Test
  void 신청_대기에서_갈_수_있는_상태는_표와_같다() {
    assertThat(RequestStatus.PENDING.canMoveTo(RequestStatus.SELECTED)).isTrue();
    assertThat(RequestStatus.PENDING.canMoveTo(RequestStatus.TIME_CLOSED)).isTrue();
    assertThat(RequestStatus.PENDING.canMoveTo(RequestStatus.EXPIRED)).isTrue();
    assertThat(RequestStatus.PENDING.canMoveTo(RequestStatus.WITHDRAWN)).isTrue();
    assertThat(RequestStatus.PENDING.canMoveTo(RequestStatus.CONFIRMED)).isFalse();
    assertThat(RequestStatus.SELECTED.canMoveTo(RequestStatus.CONFIRMED)).isTrue();
    assertThat(RequestStatus.SELECTED.canMoveTo(RequestStatus.REVALIDATION_FAILED)).isTrue();
    assertThat(RequestStatus.SELECTED.canMoveTo(RequestStatus.EXPIRED)).isFalse();
  }

  @Test
  void 철회_전이는_종결_시각과_이력을_만든다() {
    VisitRequest request = pending();
    Instant at = Instant.parse("2026-11-10T01:00:00Z");

    VisitRequestStatusHistory history =
        request.moveTo(RequestStatus.WITHDRAWN, RequestStatusSource.BROKER, 9L, "사유", at);

    assertThat(request.getStatus()).isEqualTo(RequestStatus.WITHDRAWN);
    assertThat(request.getClosedAt()).isEqualTo(at);
    assertThat(request.getStatusReason()).isEqualTo("사유");
    assertThat(history.getFromStatus()).isEqualTo(RequestStatus.PENDING);
    assertThat(history.getToStatus()).isEqualTo(RequestStatus.WITHDRAWN);
    assertThat(history.getActorMembershipId()).isEqualTo(9L);
  }

  @Test
  void 시스템_전이는_구성원을_남기지_않고_종결_상태에서는_더_움직일_수_없다() {
    VisitRequest request = pending();
    Instant at = Instant.parse("2026-11-10T01:00:00Z");

    VisitRequestStatusHistory history =
        request.moveTo(RequestStatus.EXPIRED, RequestStatusSource.SYSTEM, 9L, "START_PASSED", at);

    assertThat(history.getActorMembershipId()).isNull();
    assertThatThrownBy(
            () -> request.moveTo(RequestStatus.WITHDRAWN, RequestStatusSource.BROKER, 9L, null, at))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 같은_사무소_매물이면_교차_사무소가_아니다() {
    assertThat(pending().isCrossOffice()).isFalse();
  }
}
