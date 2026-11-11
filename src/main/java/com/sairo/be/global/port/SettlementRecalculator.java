package com.sairo.be.global.port;

import java.time.Instant;
import java.util.Collection;

/** 과거 효력 사건이 정정됐을 때 영향을 받은 지난 달 월말결산 저장값을 다시 계산한다. */
public interface SettlementRecalculator {

  /** 각 시각이 속한 달(Asia/Seoul)의 저장된 결산을 다시 계산한다. 저장값이 없는 달과 현재 달은 건너뛴다. */
  void recalculate(Long officeId, Collection<Instant> effectiveTimes);

  /** 방문 결과 정정용이다. 주어진 시각들과 그 조율의 최초 종료 시각이 속한 달을 다시 계산한다. */
  void recalculateForCoordination(
      Long officeId, Long coordinationId, Collection<Instant> effectiveTimes);
}
