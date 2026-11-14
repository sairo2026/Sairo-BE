package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.domain.billing.service.BillingNoticeComposer.BillingNotice;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BillingNoticeComposerTest {

  private static Map<String, String> allValues() {
    Map<String, String> values = new HashMap<>();
    for (String key :
        new String[] {
          "남은 기간",
          "사무소명",
          "무료 종료 일시",
          "금액",
          "인원",
          "첫 결제일",
          "청구일",
          "결제수단 등록 여부",
          "구독 구매 동의 여부",
          "결제 전 상태 종료 일시",
          "실패 사유",
          "제한 시작 일시",
          "종료 예정 일시",
          "반환 기간 종료 일시",
          "효력 일시",
          "현재 금액",
          "새 금액",
          "적용 청구 기간 시작 일시",
          "응답 기한",
          "보장 종료 일시",
          "결제 예정일",
          "카드사",
          "끝4자리",
          "지원 이메일"
        }) {
      values.put(key, "[" + key + "]");
    }
    return values;
  }

  @Test
  void 모든_값이_있으면_모든_템플릿이_빈_자리_없이_채워진다() {
    for (BillingNoticeTemplate template : BillingNoticeTemplate.values()) {
      BillingNotice notice = BillingNoticeComposer.compose(template, allValues());

      assertThat(notice.title()).as(template.name()).startsWith("[사이로] ").doesNotContain("{");
      assertThat(notice.emailBody()).as(template.name()).doesNotContain("{").doesNotContain("}");
      assertThat(notice.emailBody()).as(template.name()).endsWith("이 메일은 서비스 이용에 필요한 안내입니다.");
    }
  }

  @Test
  void 무료_종료_안내는_문서의_문장과_서비스_안_알림_문구를_그대로_쓴다() {
    BillingNotice notice =
        BillingNoticeComposer.compose(BillingNoticeTemplate.TRIAL_ENDING, allValues());

    assertThat(notice.title()).isEqualTo("[사이로] 무료 이용이 [남은 기간] 뒤 끝납니다");
    assertThat(notice.body())
        .startsWith("[사무소명] 대표님, 사이로 무료 이용이 [무료 종료 일시]에 끝납니다.\n")
        .contains("- 월 이용료 [금액]원(부가가치세 포함), 현재 이용 인원 [인원]명 기준\n")
        .contains("- 무료 이용 기간은 환불 대상이 아닙니다\n")
        .contains("준비 상태 [결제수단 등록 여부], [구독 구매 동의 여부]");
    assertThat(notice.actionLabel()).isEqualTo("구독 화면 열기");
    assertThat(notice.inAppText()).isEqualTo("무료 이용이 [남은 기간] 뒤 끝납니다. 구독 화면에서 결제 준비를 확인해 주세요.");
    assertThat(notice.emailFooter())
        .isEqualTo(
            "보낸 곳 사이로(SAIRO) 대표 김태환 / 사업자등록번호 704-16-02872 / 문의 [지원 이메일]\n"
                + "이 메일은 서비스 이용에 필요한 안내입니다.");
  }

  @Test
  void 값이_없는_문장과_줄은_빼고_보낸다() {
    Map<String, String> values = allValues();
    values.remove("첫 결제일");
    values.remove("결제수단 등록 여부");
    values.remove("지원 이메일");

    BillingNotice notice =
        BillingNoticeComposer.compose(BillingNoticeTemplate.TRIAL_ENDING, values);

    assertThat(notice.body())
        .doesNotContain("첫 결제 예정")
        .doesNotContain("준비 상태")
        .contains("결제수단과 구독 구매 동의가 준비되어 있어야 첫 결제가 진행됩니다.")
        .contains("- 무료 이용 기간은 환불 대상이 아닙니다");
    assertThat(notice.emailFooter()).isEqualTo("이 메일은 서비스 이용에 필요한 안내입니다.");
  }

  @Test
  void 제목에_쓰는_값이_없으면_보내지_않는다() {
    Map<String, String> values = allValues();
    values.remove("남은 기간");

    assertThatThrownBy(
            () -> BillingNoticeComposer.compose(BillingNoticeTemplate.TRIAL_ENDING, values))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 가격_인상_안내는_응답_기한_재안내_문장을_포함하고_인하_안내는_동의를_요구하지_않는다() {
    BillingNotice increase =
        BillingNoticeComposer.compose(BillingNoticeTemplate.PRICE_INCREASE, allValues());
    BillingNotice decrease =
        BillingNoticeComposer.compose(BillingNoticeTemplate.PRICE_DECREASE_OR_SAME, allValues());

    assertThat(increase.body())
        .contains("- 응답 기한 [응답 기한]")
        .contains("동의하지 않거나 응답 기한까지 응답하지 않으면")
        .contains("응답 기한 14일 전, 7일 전, 1일 전에 같은 내용을 다시 보내 드립니다.");
    assertThat(increase.actionLabel()).isEqualTo("안내 확인하고 응답하기");
    assertThat(decrease.body()).endsWith("따로 하실 일은 없습니다.");
  }

  @Test
  void 미납_안내는_제한_시작과_종료_일시를_채운다() {
    BillingNotice suspension =
        BillingNoticeComposer.compose(BillingNoticeTemplate.SUSPENSION_STARTED, allValues());
    BillingNotice ended =
        BillingNoticeComposer.compose(BillingNoticeTemplate.SUBSCRIPTION_ENDED_UNPAID, allValues());

    assertThat(suspension.title()).isEqualTo("[사이로] 미납으로 업무 입력이 제한됩니다");
    assertThat(suspension.body())
        .contains("[제한 시작 일시]부터 자료 조회와 내보내기, 계정 관리, 결제, 해지만 할 수 있습니다.")
        .contains("[종료 예정 일시]까지 결제하지 않으면 미납 청구를 취소하고 구독이 종료됩니다.");
    assertThat(ended.body()).contains("종료 후 30일 동안([반환 기간 종료 일시]까지)");
  }
}
