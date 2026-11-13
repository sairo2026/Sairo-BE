package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.repository.ConsentNoticeBatchRepository;
import com.sairo.be.domain.auth.repository.ConsentNoticeBatchRepository.ReconfirmCandidate;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 광고성 정보 수신 동의 후 2년 주기의 재확인 안내 대상을 만든다. 주기가 끝나는 날의 전날이 발송 기한이고 그 30일 전부터 보낼 수 있다. 최신 동의가 철회 상태이거나
 * 시행 버전과 다른 버전이면 만들지 않는다. 지난 주기는 소급해 만들지 않는다.
 */
@Profile("!migrate")
@Service
public class MarketingReconfirmScanService {

  public static final int CYCLE_YEARS = 2;
  public static final Duration LEAD = Duration.ofDays(30);

  private final ConsentNoticeBatchRepository repository;
  private final TermsCatalogService termsCatalogService;
  private final Clock clock;

  public MarketingReconfirmScanService(
      ConsentNoticeBatchRepository repository,
      TermsCatalogService termsCatalogService,
      Clock clock) {
    this.repository = repository;
    this.termsCatalogService = termsCatalogService;
    this.clock = clock;
  }

  /** 현재 시각이 속한 주기 번호의 발송 기한이다. 모든 주기가 지났을 수는 없으므로 항상 값이 있다. */
  static Instant dueAt(Instant agreedAt, int cycleNo) {
    LocalDate end = TimeRules.displayDate(agreedAt).plusYears((long) CYCLE_YEARS * cycleNo);
    return TimeRules.startOfDisplayDay(end);
  }

  static int currentCycle(Instant agreedAt, Instant now) {
    int cycle = 1;
    while (!now.isBefore(dueAt(agreedAt, cycle))) {
      cycle++;
    }
    return cycle;
  }

  /** 첫 주기의 목표 시각이 지났을 수 있는 가장 늦은 동의 시각이다. 날짜 경계 여유로 하루를 더 둔다. */
  static Instant earliestAgreement(Instant now) {
    LocalDate day = TimeRules.displayDate(now).minusYears(CYCLE_YEARS).plusDays(LEAD.toDays() + 1);
    return TimeRules.endOfDisplayDayExclusive(day);
  }

  @Transactional
  public int scan() {
    Instant now = clock.instant();
    Optional<TermsVersionView> inForce =
        termsCatalogService.findInForceByCode(MarketingConsentService.MARKETING);
    if (inForce.isEmpty()) {
      return 0;
    }
    List<ReconfirmCandidate> candidates =
        repository.reconfirmCandidates(inForce.get().version(), earliestAgreement(now));
    int created = 0;
    for (ReconfirmCandidate candidate : candidates) {
      int cycle = currentCycle(candidate.agreedAt(), now);
      Instant due = dueAt(candidate.agreedAt(), cycle);
      Instant target = due.minus(LEAD);
      if (now.isBefore(target)) {
        continue;
      }
      if (repository.insertReconfirm(candidate, cycle, target, due, now)) {
        created++;
      }
    }
    return created;
  }
}
