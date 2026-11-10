package com.sairo.be.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

  private static final String RFC_SECRET =
      TotpService.encodeBase32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

  private final TotpService totpService = new TotpService();

  @Test
  void RFC6238_시험_값과_같은_코드를_만든다() {
    assertThat(totpService.codeAt(RFC_SECRET, 59L / 30)).isEqualTo("287082");
    assertThat(totpService.codeAt(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
    assertThat(totpService.codeAt(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
  }

  @Test
  void 검증은_앞뒤_한_구간까지_받고_그_밖은_거절한다() {
    Instant now = Instant.ofEpochSecond(1111111109L);
    long counter = 1111111109L / 30;
    assertThat(totpService.verify(RFC_SECRET, totpService.codeAt(RFC_SECRET, counter), now))
        .isTrue();
    assertThat(totpService.verify(RFC_SECRET, totpService.codeAt(RFC_SECRET, counter - 1), now))
        .isTrue();
    assertThat(totpService.verify(RFC_SECRET, totpService.codeAt(RFC_SECRET, counter + 1), now))
        .isTrue();
    assertThat(totpService.verify(RFC_SECRET, totpService.codeAt(RFC_SECRET, counter + 2), now))
        .isFalse();
    assertThat(totpService.verify(RFC_SECRET, "12345", now)).isFalse();
    assertThat(totpService.verify(RFC_SECRET, "abcdef", now)).isFalse();
    assertThat(totpService.verify(RFC_SECRET, null, now)).isFalse();
  }

  @Test
  void 새_비밀값은_base32_32자이고_복원하면_20바이트다() {
    String secret = totpService.newSecret();
    assertThat(secret).matches("[A-Z2-7]{32}");
    assertThat(TotpService.decodeBase32(secret)).hasSize(20);
    assertThat(totpService.newSecret()).isNotEqualTo(secret);
    assertThat(totpService.otpauthUri(secret))
        .isEqualTo("otpauth://totp/SAIRO:ops?secret=" + secret + "&issuer=SAIRO");
  }
}
