package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.billing.service.SystemJobFailure;
import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.ops.repository.OpsConsoleQueryRepository;
import com.sairo.be.domain.ops.repository.OpsConsoleQueryRepository.GuestIntakeDeadlineCounts;
import com.sairo.be.global.support.TimeRules;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 비회원 권리 요청 접수의 내부 기한 점검이다. 운영 홈의 권리요청 기한임박 건수는 같은 기준으로 조회할 때마다 센다. 이 배치는 매일 한 번 건수를 기록하고 기한이 지난
 * 접수가 있으면 시스템 작업 실패 행으로 드러낸다.
 */
@Slf4j
@Profile("!migrate")
@Service
public class GuestIntakeDeadlineScanService {

  public static final String JOB_NAME = "guest-intake-deadline-scan";
  public static final String OVERDUE_CODE = "GUEST_INTAKE_OVERDUE";

  private final OpsConsoleQueryRepository queries;
  private final SystemJobRunService jobRunService;

  public GuestIntakeDeadlineScanService(
      OpsConsoleQueryRepository queries, SystemJobRunService jobRunService) {
    this.queries = queries;
    this.jobRunService = jobRunService;
  }

  public boolean runScheduled(Instant now) {
    LocalDate day = TimeRules.displayDate(now);
    return jobRunService.runOnce(
        JOB_NAME,
        null,
        JOB_NAME + ":" + day,
        TimeRules.startOfDisplayDay(day).plus(Duration.ofMinutes(9 * 60 + 10)),
        () -> scan(now));
  }

  GuestIntakeDeadlineCounts scan(Instant now) {
    GuestIntakeDeadlineCounts counts =
        queries.guestIntakeDeadlineCounts(now, now.plus(OpsHomeService.PRIVACY_DEADLINE_WINDOW));
    log.info("비회원 권리 요청 접수 기한 점검: 기한 임박 {}건, 기한 초과 {}건", counts.approaching(), counts.overdue());
    if (counts.overdue() > 0) {
      throw new SystemJobFailure(OVERDUE_CODE);
    }
    return counts;
  }
}
