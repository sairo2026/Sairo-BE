package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.repository.VisitRequestReadRepository;
import com.sairo.be.domain.visit.repository.VisitRequestReadRepository.OverdueRow;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Slf4j
@Profile("!migrate")
@Service
public class VisitRequestExpiryService {

  public static final int BATCH_SIZE = 200;
  private static final int MAX_ROUNDS = 10;

  private final VisitRequestReadRepository readRepository;
  private final VisitRequestExpiryProcessor processor;
  private final Clock clock;

  public VisitRequestExpiryService(
      VisitRequestReadRepository readRepository,
      VisitRequestExpiryProcessor processor,
      Clock clock) {
    this.readRepository = readRepository;
    this.processor = processor;
    this.clock = clock;
  }

  public int expireOverdue() {
    int expired = 0;
    for (int round = 0; round < MAX_ROUNDS; round++) {
      List<OverdueRow> rows = readRepository.findOverdue(clock.instant(), BATCH_SIZE);
      int progressed = 0;
      for (OverdueRow row : rows) {
        try {
          if (processor.expire(row.id(), row.coordinationId())) {
            progressed++;
          }
        } catch (RuntimeException e) {
          log.warn("방문 신청 만료 처리에 실패했습니다. requestId={}", row.id(), e);
        }
      }
      expired += progressed;
      if (rows.size() < BATCH_SIZE || progressed == 0) {
        break;
      }
    }
    return expired;
  }
}
