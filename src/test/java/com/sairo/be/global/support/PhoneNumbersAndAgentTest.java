package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersAndAgentTest {

  @ParameterizedTest
  @CsvSource({
    "010-1234-5678,010-1234-5678",
    "01012345678,010-1234-5678",
    "010-123-4567,010-123-4567",
    "  010-1234-5678 ,010-1234-5678",
    "011-1234-5678,011-1234-5678"
  })
  void 휴대폰_번호는_하이픈_형식으로_정규화한다(String raw, String expected) {
    assertThat(PhoneNumbers.normalize(raw)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"02-123-4567", "010-12-5678", "abc", "", "010-1234-56789", "+82-10-1234-5678"})
  void 형식이_아니면_비어_있다(String raw) {
    assertThat(PhoneNumbers.normalize(raw)).isEmpty();
  }

  @Test
  void null은_비어_있다() {
    assertThat(PhoneNumbers.normalize(null)).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36|Chrome on macOS",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 Edg/120.0|Edge on Windows",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Safari/604.1|Safari on iOS",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36|Chrome on Android",
        "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0|Firefox on Linux"
      })
  void 사용자_환경은_브라우저와_OS_요약으로_가공한다(String raw, String expected) {
    assertThat(UserAgentSummarizer.summarize(raw)).isEqualTo(expected);
  }

  @Test
  void 사용자_환경이_없으면_알_수_없음이다() {
    assertThat(UserAgentSummarizer.summarize(null)).isEqualTo("알 수 없음");
    assertThat(UserAgentSummarizer.summarize(" ")).isEqualTo("알 수 없음");
  }
}
