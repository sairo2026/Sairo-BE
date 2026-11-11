package com.sairo.be.domain.contract.service;

import com.sairo.be.domain.contract.entity.ContractRow;
import com.sairo.be.domain.contract.entity.ContractStatus;
import com.sairo.be.domain.contract.entity.LeasePeriodStatus;
import com.sairo.be.domain.contract.repository.ContractHistoryRepository;
import com.sairo.be.domain.contract.repository.ContractRepository;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 체결된 전세·월세의 임대기간 표시값을 날짜에 맞춰 바꾸는 매일 배치다. 시작일에 기간 중, 만기일 다음 날 만기 도래·확인 필요가 된다. 계약 상태는 바꾸지 않는다. 처리자는
 * 비워 두고 효력시각은 표시값이 바뀐 날의 0시(Asia/Seoul)다.
 */
@Profile("!migrate")
@Service
public class LeasePeriodService {

  private final ContractRepository contracts;
  private final ContractHistoryRepository histories;
  private final ContractSupport support;
  private final TransactionTemplate transactionTemplate;

  public LeasePeriodService(
      ContractRepository contracts,
      ContractHistoryRepository histories,
      ContractSupport support,
      TransactionTemplate transactionTemplate) {
    this.contracts = contracts;
    this.histories = histories;
    this.support = support;
    this.transactionTemplate = transactionTemplate;
  }

  /** 바꾼 계약 수를 돌려준다. 계약마다 따로 커밋한다. */
  public int refreshAll() {
    Instant now = support.now();
    LocalDate today = ContractSupport.today(now);
    int changed = 0;
    for (Long id : contracts.leaseStatusOutdated(today)) {
      Boolean done = transactionTemplate.execute(status -> refresh(id, today, now));
      if (Boolean.TRUE.equals(done)) {
        changed++;
      }
    }
    return changed;
  }

  private boolean refresh(Long contractId, LocalDate today, Instant now) {
    Optional<ContractRow> locked = contracts.lockById(contractId);
    if (locked.isEmpty() || locked.get().contractStatus() != ContractStatus.SIGNED) {
      return false;
    }
    ContractRow row = locked.get();
    LeasePeriodStatus next = ContractSupport.leaseStatus(row.values(), today);
    if (next == null || next == row.leasePeriodStatus()) {
      return false;
    }
    Instant effectiveAt =
        switch (next) {
          case IN_PROGRESS -> TimeRules.startOfDisplayDay(row.values().leaseStartDate());
          case MATURITY_CONFIRMATION_NEEDED ->
              TimeRules.startOfDisplayDay(row.values().leaseEndDate().plusDays(1));
          case BEFORE_START -> now;
        };
    if (effectiveAt.isAfter(now)) {
      effectiveAt = now;
    }
    histories.insertStatus(
        contractId,
        ContractSupport.TRACK_LEASE,
        row.leasePeriodStatus() == null ? null : row.leasePeriodStatus().name(),
        next.name(),
        null,
        null,
        effectiveAt,
        null,
        now);
    contracts.updateStatus(contractId, row.contractStatus(), next, row.settlementStatus(), now);
    return true;
  }
}
