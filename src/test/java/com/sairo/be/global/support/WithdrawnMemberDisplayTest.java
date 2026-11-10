package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WithdrawnMemberDisplayTest {

  @Test
  void 탈퇴하지_않은_구성원은_저장된_이름을_그대로_돌려준다() {
    assertThat(WithdrawnMemberDisplay.name(false, "김중개", 3)).isEqualTo("김중개");
  }

  @Test
  void 탈퇴한_구성원은_대체_번호를_붙여_돌려준다() {
    assertThat(WithdrawnMemberDisplay.name(true, "탈퇴한 구성원", 2)).isEqualTo("탈퇴한 구성원 2");
  }

  @Test
  void 번호가_아직_없으면_접두어만_돌려준다() {
    assertThat(WithdrawnMemberDisplay.name(true, "김중개", null)).isEqualTo("탈퇴한 구성원");
  }

  @Test
  void 법정_신분을_함께_표시한다() {
    assertThat(WithdrawnMemberDisplay.nameWithRole(true, "x", 2, "소속공인중개사"))
        .isEqualTo("탈퇴한 구성원 2(소속공인중개사)");
    assertThat(WithdrawnMemberDisplay.nameWithRole(false, "김중개", 2, "소속공인중개사")).isEqualTo("김중개");
  }
}
