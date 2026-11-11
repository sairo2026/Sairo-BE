package com.sairo.be.domain.settlement.repository;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 한 달 집계 범위다. from·to는 사건 효력시각 경계(to는 다음 달 1일 0시, 배타)이고 미래 효력 사건은 받지 않으므로 현재 달은 지금까지만 센다.
 * firstDay·lastDay는 예정일 기준 날짜 범위(그 달 전체), cutoffDay는 실제 날짜 기준 마지막 날(지난 달은 말일, 현재 달은 오늘)이다.
 */
public record SettlementWindow(
    Long officeId,
    Instant from,
    Instant to,
    LocalDate firstDay,
    LocalDate lastDay,
    LocalDate cutoffDay) {}
