package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BusinessNumbersTest {

  @ParameterizedTest
  @ValueSource(strings = {"1234567891", "7041602872"})
  void 검증번호가_맞는_10자리_숫자는_유효하다(String value) {
    assertThat(BusinessNumbers.hasValidChecksum(value)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"1234567890", "123456789", "12345678912", "123-45-67891", "abcdefghij", ""})
  void 형식이나_검증번호가_틀리면_유효하지_않다(String value) {
    assertThat(BusinessNumbers.hasValidChecksum(value)).isFalse();
  }

  @Test
  void 하이픈과_앞뒤_공백은_정규화하고_숫자_10자리가_아니면_비어_있다() {
    assertThat(BusinessNumbers.normalize(" 123-45-67891 ")).contains("1234567891");
    assertThat(BusinessNumbers.normalize("1234567891")).contains("1234567891");
    assertThat(BusinessNumbers.normalize("123-45-6789")).isEmpty();
    assertThat(BusinessNumbers.normalize("abc-de-fghij")).isEmpty();
    assertThat(BusinessNumbers.normalize(null)).isEmpty();
  }

  @Test
  void null은_유효하지_않다() {
    assertThat(BusinessNumbers.hasValidChecksum(null)).isFalse();
  }
}
