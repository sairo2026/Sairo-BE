package com.sairo.be.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy();

  private void assertRejected(String password, String fieldErrorCode) {
    assertThatThrownBy(() -> policy.validateNewPassword("password", password))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
              assertThat(e.getFieldErrors()).hasSize(1);
              assertThat(e.getFieldErrors().get(0).field()).isEqualTo("password");
              assertThat(e.getFieldErrors().get(0).code().name()).isEqualTo(fieldErrorCode);
            });
  }

  @Test
  void 코드_포인트_8자와_12자는_허용한다() {
    assertThatCode(() -> policy.validateNewPassword("password", "Qx7!mK2a"))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.validateNewPassword("password", "Qx7!mK2aZp9#"))
        .doesNotThrowAnyException();
  }

  @Test
  void 코드_포인트_7자는_너무_짧고_13자는_너무_길다() {
    assertRejected("Qx7!mK2", "TOO_SHORT");
    assertRejected("Qx7!mK2aZp9#w", "TOO_LONG");
  }

  @Test
  void 이모지는_UTF16_길이가_아니라_코드_포인트로_센다() {
    String eightEmoji = "😀".repeat(8);
    assertThat(eightEmoji.length()).isEqualTo(16);

    assertThatCode(() -> policy.validateNewPassword("password", eightEmoji))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.validateNewPassword("password", "😀".repeat(12)))
        .doesNotThrowAnyException();
    assertRejected("😀".repeat(7), "TOO_SHORT");
    assertRejected("😀".repeat(13), "TOO_LONG");
  }

  @Test
  void 결합_문자와_한글도_코드_포인트_단위로_센다() {
    assertThatCode(() -> policy.validateNewPassword("password", "가나다라마바사아"))
        .doesNotThrowAnyException();
    assertRejected("가나다라마바사", "TOO_SHORT");
    assertThatCode(() -> policy.validateNewPassword("password", "ébcdefgh"))
        .doesNotThrowAnyException();
  }

  @Test
  void 공백도_길이에_포함하고_앞뒤_공백을_제거하지_않는다() {
    assertThatCode(() -> policy.validateNewPassword("password", "  Qx7!mK  "))
        .doesNotThrowAnyException();
    assertRejected(" Qx7!m ", "TOO_SHORT");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"password", "Password1", "PASSWORD123", "12345678", "qwerty123", "1Q2W3E4R"})
  void 흔한_비밀번호는_대소문자와_무관하게_거절한다(String password) {
    assertRejected(password, "NOT_ALLOWED");
  }

  @Test
  void 비어_있는_값은_REQUIRED다() {
    assertRejected(null, "REQUIRED");
    assertRejected("", "REQUIRED");
  }

  @ParameterizedTest
  @ValueSource(strings = {" ", "        ", "            ", "\t\n      ", "\u00a0\u3000      "})
  void 공백만으로_된_값은_길이와_무관하게_REQUIRED다(String password) {
    assertRejected(password, "REQUIRED");
  }

  @Test
  void 공백이_아닌_문자가_하나라도_있으면_공백_규칙에_걸리지_않는다() {
    assertThatCode(() -> policy.validateNewPassword("password", "       z"))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.validateNewPassword("password", "Qx 7! mK"))
        .doesNotThrowAnyException();
  }
}
