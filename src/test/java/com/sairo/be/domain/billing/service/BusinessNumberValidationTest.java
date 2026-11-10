package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BusinessNumberValidationTest {

  @ParameterizedTest
  @ValueSource(strings = {"1234567891", "7041602872"})
  void 검증번호가_맞는_10자리_숫자는_유효하다(String value) {
    assertThat(TaxRecipientService.isValidBusinessNumber(value)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"1234567890", "123456789", "12345678912", "123-45-67891", "abcdefghij", ""})
  void 형식이나_검증번호가_틀리면_유효하지_않다(String value) {
    assertThat(TaxRecipientService.isValidBusinessNumber(value)).isFalse();
  }

  @Test
  void null은_유효하지_않다() {
    assertThat(TaxRecipientService.isValidBusinessNumber(null)).isFalse();
  }
}
