package com.sairo.be.domain.auth.service;

import com.sairo.be.global.config.CompanyProperties;
import com.sairo.be.global.port.MailSender.MailMessage;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 법률문구 04 문서의 처리 결과 통지와 2년 재확인 이메일을 만든다. 수신 동의 상태의 메일에는 수신 거부 링크를 넣는다. */
@Profile("!migrate")
@Component
public class ConsentNoticeMailComposer {

  static final String RESULT_SUBJECT = "[사이로] 공지·혜택 안내 수신 설정이 변경되었습니다";
  static final String RECONFIRM_SUBJECT = "[사이로] 공지·혜택 안내 수신 동의 내역을 알려 드립니다";
  static final String DEFAULT_SENDER = "사이로";
  static final String CONSENT_FOOTER =
      "이 메시지는 공지·혜택 안내 수신에 동의한 분께 보냅니다. 수신을 원하지 않으면 [수신 거부]를 누르거나 사이로 내 정보에서 끌 수 있습니다.";

  private final CompanyProperties company;
  private final MarketingUnsubscribeTokens tokens;
  private final String frontendBaseUrl;

  public ConsentNoticeMailComposer(
      CompanyProperties company,
      MarketingUnsubscribeTokens tokens,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.company = company;
    this.tokens = tokens;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  public String unsubscribeUrl(Long userId) {
    return frontendBaseUrl + "/unsubscribe?token=" + tokens.issue(userId);
  }

  public MailMessage result(
      String email,
      Long userId,
      String name,
      Instant expressedAt,
      boolean agreed,
      boolean viaUnsubscribeLink) {
    String request = agreed ? "수신 동의" : viaUnsubscribeLink ? "수신 거부" : "수신 동의 철회";
    String outcome =
        agreed
            ? "앞으로 공지·혜택 안내를 보내 드립니다."
            : "공지·혜택 안내 발송 대상에서 제외했습니다. 계정 보안 알림과 결제 등 서비스 이용에 필요한 안내는 계속 보내 드립니다.";
    StringBuilder body = new StringBuilder();
    body.append(name)
        .append("님이 요청하신 공지·혜택 안내 수신 설정 변경을 처리했습니다. 요청 내용 ")
        .append(request)
        .append(". 요청하신 날짜 ")
        .append(koreanDate(expressedAt))
        .append(". 처리 결과 ")
        .append(outcome)
        .append(' ')
        .append(sender())
        .append(" 설정은 사이로 내 정보 화면에서 언제든 바꿀 수 있습니다.");
    if (agreed) {
      appendUnsubscribe(body, userId);
    }
    return new MailMessage(email, RESULT_SUBJECT, body.toString());
  }

  public MailMessage reconfirm(String email, Long userId, String name, Instant agreedAt) {
    StringBuilder body = new StringBuilder();
    body.append(name)
        .append("님은 ")
        .append(koreanDate(agreedAt))
        .append("에 사이로 공지·혜택 안내 수신에 동의하셨습니다. 계속 받으시려면 따로 하실 일이 없습니다. 지금 동의가 그대로 유지됩니다.")
        .append(" 더 받지 않으시려면 [수신 거부]를 누르거나 사이로 내 정보 화면에서 \"이메일로 공지·혜택 안내 받기\"를 꺼 주세요.")
        .append(" 철회에는 비용이 들지 않습니다. ")
        .append(sender());
    appendUnsubscribe(body, userId);
    return new MailMessage(email, RECONFIRM_SUBJECT, body.toString());
  }

  private void appendUnsubscribe(StringBuilder body, Long userId) {
    body.append("\n\n").append(CONSENT_FOOTER).append("\n[수신 거부] ").append(unsubscribeUrl(userId));
  }

  private String sender() {
    String name = blank(company.businessName()) ? DEFAULT_SENDER : company.businessName().strip();
    String text = "보낸 곳 " + name;
    if (!blank(company.supportEmail())) {
      text += " / 문의 " + company.supportEmail().strip();
    }
    return text + ".";
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  static String koreanDate(Instant instant) {
    LocalDate date = TimeRules.displayDate(instant);
    return date.getYear() + "년 " + date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
  }
}
