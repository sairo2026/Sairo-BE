package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.ops.repository.OpsContentRepository;
import com.sairo.be.domain.ops.repository.OpsContentRepository.UpcomingTerms;
import com.sairo.be.global.port.NotificationPublisher;
import com.sairo.be.global.port.NotificationPublisher.Notice;
import com.sairo.be.global.support.TimeRules;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 약관 카탈로그에 시행 시각이 정해진 새 버전이 있으면 시행 14일 전에 한 번 알린다. 이용약관과 개인정보처리방침은 탈퇴하지 않은 모든 회원의 알림함에 남기고, 구독 구매
 * 약관은 끝나지 않은 구독이 있는 사무소의 대표에게 남긴다. 시행까지 14일보다 적게 남은 상태에서 처음 확인하면 그때 바로 보낸다.
 */
@Profile("!migrate")
@Service
public class TermsEffectiveNoticeService {

  public static final String JOB_NAME = "terms-effective-notice";
  static final String KEY_PREFIX = "terms-effective-notice:";
  static final Duration LEAD = Duration.ofDays(14);
  static final String PURCHASE_TERMS_CODE = "SUBSCRIPTION_PURCHASE";

  private final OpsContentRepository repository;
  private final NotificationPublisher publisher;
  private final SystemJobRunService jobRunService;

  public TermsEffectiveNoticeService(
      OpsContentRepository repository,
      NotificationPublisher publisher,
      SystemJobRunService jobRunService) {
    this.repository = repository;
    this.publisher = publisher;
    this.jobRunService = jobRunService;
  }

  public void sendDue(Instant now) {
    for (UpcomingTerms terms : repository.upcomingTerms(now, now.plus(LEAD))) {
      jobRunService.runOnce(
          JOB_NAME,
          null,
          KEY_PREFIX + terms.termsCode() + ":" + terms.termsVersion(),
          terms.effectiveAt().minus(LEAD),
          () -> notify(terms));
    }
  }

  private void notify(UpcomingTerms terms) {
    String message = message(terms);
    if (PURCHASE_TERMS_CODE.equals(terms.termsCode())) {
      for (Long officeId : repository.officeIdsWithOpenSubscription()) {
        publisher.publishToOwners(
            officeId, new Notice(officeId, null, "PLAN_CHANGED", message, "SUBSCRIPTION", null));
      }
      return;
    }
    for (Long userId : repository.activeUserIds()) {
      publisher.publish(new Notice(null, userId, "TERMS_CHANGED", message, "NONE", null));
    }
  }

  static String message(UpcomingTerms terms) {
    String date = koreanDate(TimeRules.displayDate(terms.effectiveAt()));
    String summary = summary(terms);
    return switch (terms.termsCode()) {
      case "TOS" -> {
        String text = date + "부터 사이로 이용약관이 바뀝니다. 주요 변경 내용 " + summary + ".";
        if (terms.reconsentRequired()) {
          text +=
              " 시행 후 첫 로그인 때 변경 내용을 확인하고 동의해 주세요. 동의하지 않으면 서비스를 계속 이용할 수 없으며 이용을 멈추려면 회원 탈퇴를 할 수 있습니다.";
        }
        yield text;
      }
      case "PRIVACY" -> date + "부터 개인정보처리방침이 바뀝니다. 바뀌는 내용 " + summary + ".";
      default ->
          date
              + "부터 구독·결제 약관이 바뀝니다. 바뀌는 내용 "
              + summary
              + ". 시행일부터 구독 화면에서 새 약관으로 구독 구매 동의를 다시 해 주세요. 동의가 없으면 다음 갱신 결제가 진행되지 않습니다.";
    };
  }

  private static String summary(UpcomingTerms terms) {
    String text = terms.changeSummary() == null ? "" : terms.changeSummary().strip();
    return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
  }

  private static String koreanDate(LocalDate date) {
    return date.getYear() + "년 " + date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
  }
}
