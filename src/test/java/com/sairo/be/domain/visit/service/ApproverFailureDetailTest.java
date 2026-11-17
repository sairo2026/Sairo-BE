package com.sairo.be.domain.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApproverFailureDetailTest {

  private static final String GENERIC = "신청한 사무소 또는 고객 쪽 사정으로 확정할 수 없다.";

  @Test
  void 승인자_조건_위반만_있으면_그대로_보여_준다() {
    assertThat(ApproverFailureDetail.of("위반한 규칙: WINDOW_MISMATCH, MIN_GAP"))
        .isEqualTo("위반한 규칙: WINDOW_MISMATCH, MIN_GAP");
  }

  @Test
  void 신청_쪽_사정이_섞이면_그_규칙은_가리고_일반_문구를_덧붙인다() {
    assertThat(ApproverFailureDetail.of("위반한 규칙: WINDOW_MISMATCH, BROKER_CONFLICT"))
        .isEqualTo("위반한 규칙: WINDOW_MISMATCH. " + GENERIC);
  }

  @Test
  void 신청_쪽_사정만_있으면_일반_문구만_보여_준다() {
    for (String rule :
        new String[] {
          "BROKER_CONFLICT",
          "CUSTOMER_CONFLICT",
          "CUSTOMER_TIME_MISMATCH",
          "PROPERTY_CONFLICT",
          "TRAVEL_CONFLICT",
          "NO_PERMISSION",
          "DEPUTY_VISIT_LOCKED"
        }) {
      assertThat(ApproverFailureDetail.of("위반한 규칙: " + rule)).isEqualTo(GENERIC);
    }
  }

  @Test
  void 알_수_없는_형식은_일반_문구로_가리고_없으면_없다() {
    assertThat(ApproverFailureDetail.of("다른 서술")).isEqualTo(GENERIC);
    assertThat(ApproverFailureDetail.of(null)).isNull();
  }
}
