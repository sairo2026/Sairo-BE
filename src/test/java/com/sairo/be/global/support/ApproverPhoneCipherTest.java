package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApproverPhoneCipherTest {

  private static final int COLUMN_LENGTH = 255;
  private static final int MAX_INPUT_LENGTH = 30;

  private final ApproverPhoneCipher cipher = new ApproverPhoneCipher(new TextCipher("secret-a"));

  @Test
  void 암호문은_판별_접두사로_시작하고_원문을_담지_않으며_복호화하면_돌아온다() {
    String encrypted = cipher.encrypt("010-1111-2222");

    assertThat(encrypted).startsWith(ApproverPhoneCipher.PREFIX).doesNotContain("010-1111-2222");
    assertThat(ApproverPhoneCipher.isEncrypted(encrypted)).isTrue();
    assertThat(cipher.decrypt(encrypted)).isEqualTo("010-1111-2222");
  }

  @Test
  void 접두사가_없는_기존_값은_그대로_읽는다() {
    assertThat(ApproverPhoneCipher.isEncrypted("010-1111-2222")).isFalse();
    assertThat(cipher.decrypt("010-1111-2222")).isEqualTo("010-1111-2222");
  }

  @Test
  void 값이_없으면_암호화와_복호화_모두_값이_없다() {
    assertThat(cipher.encrypt(null)).isNull();
    assertThat(cipher.decrypt(null)).isNull();
  }

  @Test
  void 최대_입력_길이의_암호문도_저장_열_길이에_들어간다() {
    String ascii = "0".repeat(MAX_INPUT_LENGTH);
    String wide = "가".repeat(MAX_INPUT_LENGTH);
    String widest = "\uD83D\uDE00".repeat(MAX_INPUT_LENGTH);

    assertThat(cipher.encrypt("010-1111-2222").length()).isLessThanOrEqualTo(COLUMN_LENGTH);
    assertThat(cipher.encrypt(ascii).length()).isLessThanOrEqualTo(COLUMN_LENGTH);
    assertThat(cipher.encrypt(wide).length()).isLessThanOrEqualTo(COLUMN_LENGTH);
    assertThat(cipher.encrypt(widest).length()).isLessThanOrEqualTo(COLUMN_LENGTH);
    assertThat(cipher.decrypt(cipher.encrypt(widest))).isEqualTo(widest);
  }
}
