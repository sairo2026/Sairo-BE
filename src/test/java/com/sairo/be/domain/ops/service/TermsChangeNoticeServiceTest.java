package com.sairo.be.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.ops.repository.OpsContentRepository.PolicyRow;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TermsChangeNoticeServiceTest {

  private static final Instant EFFECTIVE = Instant.parse("2026-12-01T00:00:00Z");

  private static PolicyRow policy(String docType, boolean requiresConsent) {
    return new PolicyRow(
        1L,
        docType,
        "2026-12-01",
        "PUBLISHED",
        "본문",
        "수신 동의 항목 정리",
        null,
        requiresConsent,
        EFFECTIVE,
        EFFECTIVE,
        1L,
        2L,
        1L);
  }

  @Test
  void 동의가_필요한_약관_변경은_동의_안내가_붙는다() {
    assertThat(TermsChangeNoticeService.message(policy("TERMS", true)))
        .startsWith("2026년 12월 1일부터 사이로 이용약관이 바뀝니다. 주요 변경 내용 수신 동의 항목 정리.")
        .contains("시행 후 첫 로그인 때 변경 내용을 확인하고 동의해 주세요");
  }

  @Test
  void 동의가_필요하지_않은_약관_변경에는_동의_안내가_없다() {
    assertThat(TermsChangeNoticeService.message(policy("TERMS", false)))
        .isEqualTo("2026년 12월 1일부터 사이로 이용약관이 바뀝니다. 주요 변경 내용 수신 동의 항목 정리.");
  }

  @Test
  void 개인정보처리방침은_바뀌는_내용을_알린다() {
    assertThat(TermsChangeNoticeService.message(policy("PRIVACY", true)))
        .isEqualTo("2026년 12월 1일부터 개인정보처리방침이 바뀝니다. 바뀌는 내용 수신 동의 항목 정리.");
  }
}
