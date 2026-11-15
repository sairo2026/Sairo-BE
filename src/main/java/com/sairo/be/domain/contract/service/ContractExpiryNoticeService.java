package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.billing.service.SystemJobSkipped;
import com.sairo.be.domain.contract.repository.ContractExpiryNoticeRepository;
import com.sairo.be.domain.contract.repository.ContractExpiryNoticeRepository.Target;
import com.sairo.be.domain.home.entity.NotificationTargetType;
import com.sairo.be.domain.home.entity.NotificationType;
import com.sairo.be.global.port.NotificationPublisher;
import com.sairo.be.global.port.NotificationPublisher.Notice;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 임대차 만기 알림 발행이다. 만기까지 남은 일수가 90일, 30일, 7일 이하가 되는 단계마다 계약당 한 번만 담당 중개사와 사무소 대표에게 알림함
 * 알림(CONTRACT_EXPIRY)을 남긴다. 단계는 Asia/Seoul 날짜로 판정하고 배치가 하루 빠져도 다음 실행이 현재 단계를 한 번 보낸다. 단계별 중복은
 * system_job_run 키로 막는다.
 */
@Profile("!migrate")
@Service
public class ContractExpiryNoticeService {

  static final String JOB_NAME = "contract-expiry-notice";
  static final List<Integer> STAGES = List.of(7, 30, 90);

  private final ContractExpiryNoticeRepository repository;
  private final NotificationPublisher publisher;
  private final SystemJobRunService jobRunService;
  private final ContractSupport support;
  private final TransactionTemplate transactionTemplate;

  public ContractExpiryNoticeService(
      ContractExpiryNoticeRepository repository,
      NotificationPublisher publisher,
      SystemJobRunService jobRunService,
      ContractSupport support,
      TransactionTemplate transactionTemplate) {
    this.repository = repository;
    this.publisher = publisher;
    this.jobRunService = jobRunService;
    this.support = support;
    this.transactionTemplate = transactionTemplate;
  }

  /** 이번 실행에서 새로 발행을 마친 계약 수를 돌려준다. 실패한 계약은 다음 실행이 다시 시도한다. */
  public int publishDue() {
    Instant now = support.now();
    LocalDate today = ContractSupport.today(now);
    int done = 0;
    for (Target target : repository.targets(today, today.plusDays(STAGES.getLast()))) {
      long daysLeft = ChronoUnit.DAYS.between(today, target.leaseEndDate());
      boolean started =
          jobRunService.runOnce(
              JOB_NAME,
              target.officeId(),
              "contract-expiry-notice:" + target.contractId() + ":D" + stageOf(daysLeft),
              now,
              () -> transactionTemplate.executeWithoutResult(status -> notify(target, daysLeft)));
      if (started) {
        done++;
      }
    }
    return done;
  }

  static int stageOf(long daysLeft) {
    return STAGES.stream().filter(stage -> daysLeft <= stage).findFirst().orElseThrow();
  }

  static String message(String propertyName, long daysLeft) {
    String name = propertyName == null || propertyName.isBlank() ? "해당" : propertyName.strip();
    return daysLeft == 0
        ? name + " 계약의 임대차 만기가 오늘입니다"
        : name + " 계약의 임대차 만기가 " + daysLeft + "일 남았습니다";
  }

  private void notify(Target target, long daysLeft) {
    List<Long> recipients =
        repository.recipientUserIds(target.officeId(), target.managingMembershipId());
    if (recipients.isEmpty()) {
      throw new SystemJobSkipped("NO_RECIPIENT");
    }
    String message = message(target.propertyName(), daysLeft);
    for (Long userId : recipients) {
      publisher.publish(
          new Notice(
              target.officeId(),
              userId,
              NotificationType.CONTRACT_EXPIRY.name(),
              message,
              NotificationTargetType.CONTRACT.name(),
              target.contractId()));
    }
  }
}
