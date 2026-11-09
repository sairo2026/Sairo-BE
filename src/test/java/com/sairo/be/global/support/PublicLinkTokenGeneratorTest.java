package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PublicLinkTokenGeneratorTest {

  private final PublicLinkTokenGenerator generator =
      new PublicLinkTokenGenerator(new PublicLinkProperties("test-secret"));

  @Test
  void 같은_입력은_같은_토큰을_만들고_해시는_64자_16진수다() {
    Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");

    String token = generator.generate(1L, issuedAt);

    assertThat(generator.generate(1L, issuedAt)).isEqualTo(token);
    assertThat(generator.generate(2L, issuedAt)).isNotEqualTo(token);
    assertThat(generator.hash(token)).matches("[0-9a-f]{64}").isNotEqualTo(token);
  }

  @Test
  void 비밀키가_다르면_토큰이_다르다() {
    Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
    var other = new PublicLinkTokenGenerator(new PublicLinkProperties("other-secret"));

    assertThat(other.generate(1L, issuedAt)).isNotEqualTo(generator.generate(1L, issuedAt));
  }
}
