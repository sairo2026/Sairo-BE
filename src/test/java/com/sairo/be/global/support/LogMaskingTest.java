package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LogMaskingTest {

  @Test
  void 이메일은_첫_글자와_도메인만_남긴다() {
    assertThat(LogMasking.email("agent@example.com")).isEqualTo("a***@example.com");
    assertThat(LogMasking.email("not-mail")).isEqualTo("***");
    assertThat(LogMasking.email(null)).isEqualTo("***");
  }

  @Test
  void 전화번호는_끝_네_자리만_남긴다() {
    assertThat(LogMasking.phone("010-1234-5678")).isEqualTo("***5678");
    assertThat(LogMasking.phone("01012345678")).isEqualTo("***5678");
    assertThat(LogMasking.phone("12")).isEqualTo("***");
  }

  @Test
  void 토큰은_앞_네_글자만_남긴다() {
    assertThat(LogMasking.token("abcd1234efgh5678")).isEqualTo("abcd***");
    assertThat(LogMasking.token("short")).isEqualTo("***");
    assertThat(LogMasking.token(null)).isEqualTo("***");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "로그인 실패 agent@example.com 계정|로그인 실패 a***@example.com 계정",
        "발송 대상 010-1234-5678 입니다|발송 대상 ***5678 입니다",
        "발송 대상 01012345678 입니다|발송 대상 ***5678 입니다",
        "GET /api/auth?token=abc123def&x=1|GET /api/auth?token=***&x=1",
        "verifiedPhoneTicket=aaa.bbb~ccc 요청|verifiedPhoneTicket=*** 요청",
        "GET /api/public/customer-visit-links/abc.def-123?x=1|GET /api/public/customer-visit-links/***?x=1",
        "PUT /api/public/approver-property-links/abc.def-123/conditions|PUT /api/public/approver-property-links/***/conditions",
        "password=hunter2|password=***",
        "code=123456 확인|code=*** 확인"
      })
  void 로그_문장_안의_민감값을_가린다(String raw, String expected) {
    assertThat(LogMasking.maskSensitive(raw)).isEqualTo(expected);
  }

  @Test
  void 민감값이_없는_문장은_그대로_둔다() {
    assertThat(LogMasking.maskSensitive("사용자 id=42 로그인")).isEqualTo("사용자 id=42 로그인");
    assertThat(LogMasking.maskSensitive(null)).isNull();
  }
}
