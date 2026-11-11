package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.service.BillingNoticeComposer.BillingNotice;
import com.sairo.be.global.port.NotificationPublisher;
import com.sairo.be.global.port.NotificationPublisher.Notice;
import com.sairo.be.global.port.OfficeDirectory;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 구독·결제 사건을 대표 알림함에 남긴다. 문구는 BillingNoticeTemplate을 BillingNoticeComposer로 채운 것이고 알림함 문구는 템플릿의
 * inAppText, 없으면 제목에서 머리표를 뺀 문장이다. 호출한 트랜잭션 안에서 남기고 외부 전달은 커밋 뒤 NotificationPublisher가 맡는다. 중복 발송은
 * 호출 지점이 막는다(상태 전이는 한 번만 일어나고, 무료 종료 안내는 system_job_run 키, 결제 실패는 청구서의 첫 실패 시도에서만 부른다).
 */
@Profile("!migrate")
@Component
public class BillingNoticeDispatcher {

  static final String TITLE_PREFIX = "[사이로] ";
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final NotificationPublisher publisher;
  private final OfficeDirectory officeDirectory;

  public BillingNoticeDispatcher(NotificationPublisher publisher, OfficeDirectory officeDirectory) {
    this.publisher = publisher;
    this.officeDirectory = officeDirectory;
  }

  /** 무료 이용 종료 안내(TRIAL_ENDING)다. remainingLabel은 "7일" 같은 남은 기간이다. */
  public List<Long> trialEnding(Long officeId, Instant trialEndsAt, String remainingLabel) {
    Map<String, String> values = base(officeId);
    values.put("남은 기간", remainingLabel);
    values.put("무료 종료 일시", format(trialEndsAt));
    return send(officeId, "TRIAL_ENDING", BillingNoticeTemplate.TRIAL_ENDING, values);
  }

  /** 무료 종료 뒤 결제 준비가 안 돼 결제 전 상태로 바뀐 안내(PAYMENT)다. */
  public List<Long> prePaymentStarted(Long officeId, Instant prePaymentEndsAt) {
    Map<String, String> values = base(officeId);
    values.put("결제 전 상태 종료 일시", format(prePaymentEndsAt));
    return send(officeId, "PAYMENT", BillingNoticeTemplate.TRIAL_ENDED_PAYMENT_NEEDED, values);
  }

  /** 정기 결제 실패 안내(PAYMENT)다. */
  public List<Long> paymentFailed(Long officeId, long amount, String failureLabel) {
    Map<String, String> values = base(officeId);
    values.put("금액", String.format("%,d", amount));
    values.put("실패 사유", failureLabel);
    return send(officeId, "PAYMENT", BillingNoticeTemplate.PAYMENT_FAILED, values);
  }

  /** 미납으로 업무 입력 제한이 시작된 안내(PAYMENT)다. */
  public List<Long> suspensionStarted(Long officeId, Instant restrictedAt, Instant endsAt) {
    Map<String, String> values = base(officeId);
    values.put("제한 시작 일시", format(restrictedAt));
    values.put("종료 예정 일시", format(endsAt));
    return send(officeId, "PAYMENT", BillingNoticeTemplate.SUSPENSION_STARTED, values);
  }

  /** 미납으로 구독이 끝난 안내(PAYMENT)다. */
  public List<Long> endedUnpaid(Long officeId, Instant endedAt, Instant returnEndsAt) {
    Map<String, String> values = base(officeId);
    values.put("종료 예정 일시", format(endedAt));
    values.put("반환 기간 종료 일시", format(returnEndsAt));
    return send(officeId, "PAYMENT", BillingNoticeTemplate.SUBSCRIPTION_ENDED_UNPAID, values);
  }

  private List<Long> send(
      Long officeId, String type, BillingNoticeTemplate template, Map<String, String> values) {
    BillingNotice notice = BillingNoticeComposer.compose(template, values);
    String message = notice.inAppText() != null ? notice.inAppText() : inAppTitle(notice.title());
    return publisher.publishToOwners(
        officeId, new Notice(officeId, null, type, message, "SUBSCRIPTION", null));
  }

  static String inAppTitle(String title) {
    return title.startsWith(TITLE_PREFIX) ? title.substring(TITLE_PREFIX.length()) : title;
  }

  private Map<String, String> base(Long officeId) {
    Map<String, String> values = new HashMap<>();
    officeDirectory
        .find(officeId)
        .ifPresent(
            office -> {
              if (office.name() != null) {
                values.put("사무소명", office.name());
              }
            });
    return values;
  }

  private static String format(Instant instant) {
    return instant == null ? null : TimeRules.toDisplay(instant).format(DATE_TIME);
  }
}
