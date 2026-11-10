package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenGeneratorTest {

  private final TokenGenerator generator = new TokenGenerator();

  @Test
  void 토큰은_URL_안전한_문자만_쓰고_매번_다르다() {
    Set<String> tokens = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      String token = generator.randomToken();
      assertThat(token).matches("[A-Za-z0-9_-]{43}");
      tokens.add(token);
    }
    assertThat(tokens).hasSize(200);
  }

  @Test
  void 토큰은_16바이트_미만을_거절한다() {
    assertThatThrownBy(() -> generator.randomToken(15))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 숫자_코드는_지정한_자릿수로_앞자리_0을_유지한다() {
    for (int i = 0; i < 200; i++) {
      assertThat(generator.numericCode(6)).matches("[0-9]{6}");
    }
    assertThatThrownBy(() -> generator.numericCode(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> generator.numericCode(10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void UUID는_표준_형식이다() {
    assertThat(generator.uuid()).matches("[0-9a-f-]{36}");
  }
}
