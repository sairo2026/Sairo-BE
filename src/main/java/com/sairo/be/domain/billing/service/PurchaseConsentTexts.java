package com.sairo.be.domain.billing.service;

import com.sairo.be.global.error.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PurchaseConsentTexts {

  public static final String CANCELLATION_TERMS =
      "해지를 예약하면 이미 결제한 이용 기간이 끝날 때까지 이용할 수 있고 그 기간의 환불은 없습니다.";
  public static final String REFUND_TERMS = "바로 종료, 인증 중단, 서비스 장애의 경우에는 남은 기간을 일할 계산하여 환불합니다.";

  public static final Map<String, String> ERROR_MESSAGES =
      Map.of(
          ErrorCode.TERMS_VERSION_CHANGED.name(), "결제 조건 안내가 새로 바뀌었습니다. 새 조건을 확인하고 다시 동의해 주세요.",
          ErrorCode.CONSENT_TERMS_MISMATCH.name(),
              "인원이 늘어 결제 금액이 동의한 금액보다 커졌습니다. 새 금액으로 다시 동의해 주세요.",
          ErrorCode.PRICE_CHANGE_NOTICE_CLOSED.name(),
              "가격 변경 안내가 이미 닫혔습니다. 구독 화면에서 현재 안내를 확인해 주세요.");

  private static final String AUTO_PAYMENT_CHECK =
      "[필수] 위 내용으로 월 선불 자동결제에 동의합니다. 결제 금액은 %s원(부가가치세 포함)이며 인원이 늘어 금액이 커지면 다시 동의받기 전에는 결제되지 않습니다.";
  private static final String CANCELLATION_REFUND_CHECK =
      "[필수] 해지와 환불 조건을 확인했습니다. " + CANCELLATION_TERMS + " " + REFUND_TERMS;

  private PurchaseConsentTexts() {}

  public static List<String> checkItems(long totalAmountInclVat) {
    return List.of(
        String.format(
            Locale.KOREA,
            AUTO_PAYMENT_CHECK,
            String.format(Locale.KOREA, "%,d", totalAmountInclVat)),
        CANCELLATION_REFUND_CHECK);
  }
}
