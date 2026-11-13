package com.sairo.be.global.port.prod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AwsSmsSenderTest {
  @Test
  void normalizesKoreanMobileNumber() {
    assertEquals("+821012345678", AwsSmsSender.toE164("010-1234-5678"));
    assertEquals("+821012345678", AwsSmsSender.toE164("+82 10 1234 5678"));
  }

  @Test
  void rejectsUnsupportedNumber() {
    assertThrows(IllegalArgumentException.class, () -> AwsSmsSender.toE164("02-1234-5678"));
  }
}
