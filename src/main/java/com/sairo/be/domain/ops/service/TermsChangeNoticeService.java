package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.ops.repository.OpsContentRepository;
import com.sairo.be.domain.ops.repository.OpsContentRepository.PolicyRow;
import com.sairo.be.global.port.NotificationPublisher;
import com.sairo.be.global.port.NotificationPublisher.Notice;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 약관과 개인정보처리방침의 게시본이 생길 때 탈퇴하지 않은 모든 회원에게 변경을 알린다. 수신 설정과 무관하게 보내며 문서마다 한 번만 보낸다. */
@Profile("!migrate")
@Service
public class TermsChangeNoticeService {

  public static final String JOB_NAME = "terms-change-notice";
  static final String KEY_PREFIX = "terms-change-notice:";
  static final Set<String> NOTICE_DOC_TYPES = Set.of("TERMS", "PRIVACY");

  private final OpsContentRepository repository;
  private final NotificationPublisher publisher;
  private final SystemJobRunService jobRunService;

  public TermsChangeNoticeService(
      OpsContentRepository repository,
      NotificationPublisher publisher,
      SystemJobRunService jobRunService) {
    this.repository = repository;
    this.publisher = publisher;
    this.jobRunService = jobRunService;
  }

  public void sendOnce(PolicyRow policy, Instant now) {
    if (!NOTICE_DOC_TYPES.contains(policy.docType())) {
      return;
    }
    String message = message(policy);
    jobRunService.runOnce(
        JOB_NAME,
        null,
        KEY_PREFIX + policy.id(),
        now,
        () -> {
          for (Long userId : repository.activeUserIds()) {
            publisher.publish(new Notice(null, userId, "TERMS_CHANGED", message, "NONE", null));
          }
        });
  }

  static String message(PolicyRow policy) {
    String date = koreanDate(TimeRules.displayDate(policy.effectiveAt()));
    String summary = policy.changeSummary().strip();
    if (policy.docType().equals("TERMS")) {
      String text = date + "부터 사이로 이용약관이 바뀝니다. 주요 변경 내용 " + summary + ".";
      if (policy.requiresConsent()) {
        text +=
            " 시행 후 첫 로그인 때 변경 내용을 확인하고 동의해 주세요. 동의하지 않으면 서비스를 계속 이용할 수 없으며 이용을 멈추려면 회원 탈퇴를 할 수 있습니다.";
      }
      return text;
    }
    return date + "부터 개인정보처리방침이 바뀝니다. 바뀌는 내용 " + summary + ".";
  }

  private static String koreanDate(LocalDate date) {
    return date.getYear() + "년 " + date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
  }
}
