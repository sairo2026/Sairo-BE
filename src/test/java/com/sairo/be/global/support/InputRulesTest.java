package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InputRulesTest {

  @Test
  void 앞뒤_공백을_제거한다() {
    assertThat(InputRules.requireName("name", "  홍길동 \t")).isEqualTo("홍길동");
    assertThat(InputRules.normalize("　가나　")).isEqualTo("가나");
    assertThat(InputRules.normalize(null)).isNull();
  }

  @Test
  void 글자_수는_공백_제거_뒤_코드_포인트로_센다() {
    assertThat(InputRules.length("  가나다  ")).isEqualTo(3);
    assertThat(InputRules.length("😀😀")).isEqualTo(2);
  }

  @Test
  void 이름은_1자에서_100자까지_허용한다() {
    assertThat(InputRules.requireName("name", "가")).isEqualTo("가");
    assertThat(InputRules.requireName("name", "가".repeat(100))).hasSize(100);
    assertField(() -> InputRules.requireName("name", "   "), FieldErrorCode.REQUIRED);
    assertField(() -> InputRules.requireName("name", null), FieldErrorCode.REQUIRED);
    assertField(() -> InputRules.requireName("name", "가".repeat(101)), FieldErrorCode.TOO_LONG);
  }

  @Test
  void 메모_길이_한도는_문서와_같다() {
    assertThat(InputRules.SHORT_MEMO_MAX).isEqualTo(500);
    assertThat(InputRules.BODY_MEMO_MAX).isEqualTo(10_000);
    assertThat(InputRules.CUSTOM_FIELD_NAME_MAX).isEqualTo(50);
    assertThat(InputRules.WORK_MEMO_MAX).isEqualTo(2_000);
    assertField(
        () -> InputRules.optionalText("memo", "가".repeat(501), InputRules.SHORT_MEMO_MAX),
        FieldErrorCode.TOO_LONG);
    assertThat(InputRules.optionalText("memo", null, InputRules.SHORT_MEMO_MAX)).isNull();
    assertThat(InputRules.optionalText("memo", "  ", InputRules.SHORT_MEMO_MAX)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script>alert(1)</script>",
        "<b>굵게</b>",
        "텍스트 <img src=x onerror=alert(1)>",
        "< script>",
        "<!-- 주석 -->",
        "</div>"
      })
  void HTML_마크업이_든_입력은_거절한다(String input) {
    assertThat(InputRules.containsHtml(input)).isTrue();
    assertField(() -> InputRules.requireName("name", input), FieldErrorCode.HTML_NOT_ALLOWED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"3 < 5", "가격 <= 100", "화살표 -> 이동", "5 > 3"})
  void 마크업이_아닌_부등호는_허용한다(String input) {
    assertThat(InputRules.containsHtml(input)).isFalse();
  }

  @Test
  void 금액은_0_이상이어야_한다() {
    assertThat(InputRules.requireNonNegativeAmount("amount", 0)).isZero();
    assertField(
        () -> InputRules.requireNonNegativeAmount("amount", -1), FieldErrorCode.OUT_OF_RANGE);
  }

  @Test
  void 면적은_0보다_크고_소수_둘째_자리까지만_허용한다() {
    assertThat(InputRules.requireArea("area", new BigDecimal("84.99"))).isNotNull();
    assertThat(InputRules.requireArea("area", new BigDecimal("84.990"))).isNotNull();
    assertField(() -> InputRules.requireArea("area", BigDecimal.ZERO), FieldErrorCode.OUT_OF_RANGE);
    assertField(
        () -> InputRules.requireArea("area", new BigDecimal("84.999")),
        FieldErrorCode.INVALID_FORMAT);
    assertField(() -> InputRules.requireArea("area", null), FieldErrorCode.REQUIRED);
  }

  private void assertField(Runnable action, FieldErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
              assertThat(exception.getFieldErrors()).hasSize(1);
              assertThat(exception.getFieldErrors().get(0).code()).isEqualTo(expected);
            });
  }
}
