package com.sairo.be.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KakaoLandingPathTest {

  @Test
  void 결제_전은_대표만_결제_화면으로_보내고_비대표는_안내_화면으로_보낸다() {
    assertThat(KakaoLandingPath.of("VERIFIED_UNPAID", "OWNER")).isEqualTo("/billing");
    assertThat(KakaoLandingPath.of("VERIFIED_UNPAID", "AGENT")).isEqualTo("/access");
    assertThat(KakaoLandingPath.of("VERIFIED_UNPAID", "DEPUTY")).isEqualTo("/access");
    assertThat(KakaoLandingPath.of("VERIFIED_UNPAID", null)).isEqualTo("/access");
  }

  @Test
  void 미납은_대표만_결제_화면으로_보내고_비대표는_홈으로_보낸다() {
    assertThat(KakaoLandingPath.of("PAYMENT_OVERDUE", "OWNER")).isEqualTo("/billing");
    assertThat(KakaoLandingPath.of("PAYMENT_OVERDUE", "AGENT")).isEqualTo("/");
    assertThat(KakaoLandingPath.of("PAYMENT_OVERDUE", "DEPUTY")).isEqualTo("/");
  }

  @Test
  void 나머지_상태의_이동_경로는_역할과_무관하다() {
    assertThat(KakaoLandingPath.of("ACTIVE", "OWNER")).isEqualTo("/");
    assertThat(KakaoLandingPath.of("PRE_VERIFICATION", null)).isEqualTo("/office/start");
    assertThat(KakaoLandingPath.of("OFFICE_UNDER_REVIEW", null)).isEqualTo("/office/review");
    assertThat(KakaoLandingPath.of("AUTH_REVOKED", "OWNER")).isEqualTo("/office/verification");
    assertThat(KakaoLandingPath.of("REMOVED", null)).isEqualTo("/removed");
    assertThat(KakaoLandingPath.of("ENDED", "OWNER")).isEqualTo("/return");
  }
}
