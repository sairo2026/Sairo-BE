package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import com.sairo.be.domain.office.repository.MembershipRecordRepository;
import com.sairo.be.domain.office.repository.MembershipRecordRepository.Row;
import com.sairo.be.domain.office.repository.OfficeMembershipHistoryRepository;
import com.sairo.be.domain.office.repository.OfficeMembershipHistoryRepository.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class MembershipExpiryService {

  private final MembershipRecordRepository repository;
  private final OfficeMembershipHistoryRepository historyRepository;
  private final Clock clock;

  public MembershipExpiryService(
      MembershipRecordRepository repository,
      OfficeMembershipHistoryRepository historyRepository,
      Clock clock) {
    this.repository = repository;
    this.historyRepository = historyRepository;
    this.clock = clock;
  }

  @Transactional
  public int expireOverdue() {
    Instant now = clock.instant();
    List<Row> overdue =
        repository.pendingOlderThan(now.minus(MembershipRules.REQUEST_VALIDITY), now);
    int expired = 0;
    for (Row candidate : overdue) {
      Row row = repository.lock(candidate.id()).orElse(null);
      if (row == null || !MembershipRules.isExpiredPending(row, now)) {
        continue;
      }
      repository.expire(row.id(), now);
      historyRepository.insert(
          row.id(),
          OfficeMembershipStatus.PENDING,
          OfficeMembershipStatus.EXPIRED,
          Actor.SYSTEM,
          null,
          "기한 만료",
          now);
      expired++;
    }
    return expired;
  }
}
