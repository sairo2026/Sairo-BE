package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LogMaskingOutputTest {

  private static final Logger LOG = LoggerFactory.getLogger(LogMaskingOutputTest.class);

  @Test
  void 콘솔_로그에는_이메일_전화번호_토큰이_가려져_출력된다(CapturedOutput output) {
    LOG.info("가입 시도 email=agent@example.com phone=010-1234-5678 token=abcdef123456");

    assertThat(output.getOut())
        .contains("a***@example.com")
        .contains("***5678")
        .contains("token=***");
    assertThat(output.getOut())
        .doesNotContain("agent@example.com")
        .doesNotContain("010-1234-5678")
        .doesNotContain("abcdef123456");
  }

  @Test
  void 가릴_값이_없는_로그는_그대로_출력된다(CapturedOutput output) {
    LOG.info("정상 로그 userId={}", 42);

    assertThat(output.getOut()).contains("정상 로그 userId=42");
  }
}
