package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.security.SecurityProperties;
import org.junit.jupiter.api.Test;

class TextCipherTest {

  private TextCipher cipher(String secret) {
    return new TextCipher(new SecurityProperties("t", "r", "k", 1, "u", secret));
  }

  @Test
  void 암호화한_값은_원문과_다르고_복호화하면_돌아온다() {
    TextCipher cipher = cipher("secret-a");

    String encrypted = cipher.encrypt("123456789");

    assertThat(encrypted).isNotEqualTo("123456789").doesNotContain("123456789");
    assertThat(cipher.decrypt(encrypted)).isEqualTo("123456789");
  }

  @Test
  void 같은_원문도_매번_다른_암호문이다() {
    TextCipher cipher = cipher("secret-a");

    assertThat(cipher.encrypt("x")).isNotEqualTo(cipher.encrypt("x"));
  }

  @Test
  void 다른_키로는_복호화할_수_없다() {
    String encrypted = cipher("secret-a").encrypt("123456789");

    assertThatThrownBy(() -> cipher("secret-b").decrypt(encrypted))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 변조한_암호문은_복호화할_수_없다() {
    TextCipher cipher = cipher("secret-a");
    String encrypted = cipher.encrypt("123456789");
    String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";

    assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
  }
}
