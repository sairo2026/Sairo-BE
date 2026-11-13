package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.auth.repository.ConsentNoticeBatchRepository;
import com.sairo.be.domain.auth.service.ConsentNoticeSendService;
import com.sairo.be.domain.auth.service.ConsentNoticeSendService.Result;
import com.sairo.be.domain.auth.service.MarketingReconfirmScanService;
import com.sairo.be.domain.billing.service.SystemJobFailure;
import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.ops.repository.OpsSystemRepository;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 수신 설정 통지 발송(10분마다)과 2년 재확인 대상 생성(매일 02:30)의 실행 기록이다. 발송은 매번 기록을 남기지 않고 발송 기한 7일 전까지 보내지 못한 통지만
 * 통지마다 한 번 실패 행으로 드러낸다. 재확인 대상 생성은 `{작업 이름}:{KST 날짜}` 키로 하루 한 번 돈다.
 */
@Slf4j
@Profile("!migrate")
@Service
public class ConsentNoticeJobService {

  public static final String SEND_JOB = "consent-notice-send";
  public static final String RECONFIRM_JOB = "marketing-reconfirm-scan";
  public static final Duration WARNING_BEFORE_DUE = Duration.ofDays(7);
  public static final String RISK_CODE = "NOTICE_DEADLINE_AT_RISK";

  private final ConsentNoticeSendService sendService;
  private final MarketingReconfirmScanService scanService;
  private final ConsentNoticeBatchRepository repository;
  private final OpsSystemRepository systemRepository;
  private final SystemJobRunService jobRunService;
  private final Clock clock;

  public ConsentNoticeJobService(
      ConsentNoticeSendService sendService,
      MarketingReconfirmScanService scanService,
      ConsentNoticeBatchRepository repository,
      OpsSystemRepository systemRepository,
      SystemJobRunService jobRunService,
      Clock clock) {
    this.sendService = sendService;
    this.scanService = scanService;
    this.repository = repository;
    this.systemRepository = systemRepository;
    this.jobRunService = jobRunService;
    this.clock = clock;
  }

  public Result send() {
    Result result = sendService.sendDue();
    if (result.sent() + result.skipped() + result.retried() + result.closed() > 0) {
      log.info(
          "수신 설정 통지 발송: 발송 {}건, 이메일 없음 {}건, 재시도 예약 {}건, 종결 {}건",
          result.sent(),
          result.skipped(),
          result.retried(),
          result.closed());
    }
    warnAtRisk(clock.instant());
    return result;
  }

  void warnAtRisk(Instant now) {
    for (Long id : repository.atRiskIds(now.plus(WARNING_BEFORE_DUE))) {
      String key = SEND_JOB + ":risk:" + id;
      if (systemRepository.jobKeyExists(key)) {
        continue;
      }
      jobRunService.runOnce(
          SEND_JOB,
          null,
          key,
          now,
          () -> {
            throw new SystemJobFailure(RISK_CODE);
          });
    }
  }

  public boolean scanReconfirm(Instant now) {
    LocalDate day = TimeRules.displayDate(now);
    return jobRunService.runOnce(
        RECONFIRM_JOB,
        null,
        RECONFIRM_JOB + ":" + day,
        TimeRules.startOfDisplayDay(day).plus(Duration.ofMinutes(2 * 60 + 30)),
        () -> log.info("광고성 수신 재확인 대상 생성: {}건", scanService.scan()));
  }
}
