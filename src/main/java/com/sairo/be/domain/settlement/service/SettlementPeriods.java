package com.sairo.be.domain.settlement.service;

import com.sairo.be.domain.settlement.repository.SettlementWindow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.regex.Pattern;

/** 결산 월 경로 값의 검사와 Asia/Seoul 기준 집계 범위 계산이다. */
public final class SettlementPeriods {

  private static final Pattern FORMAT = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

  private SettlementPeriods() {}

  public static YearMonth current(Instant now) {
    return YearMonth.from(TimeRules.toDisplay(now));
  }

  public static YearMonth of(Instant instant) {
    return YearMonth.from(TimeRules.toDisplay(instant));
  }

  /** YYYY-MM 형식이고 사무소 생성월부터 현재 달 사이여야 한다. 아니면 400 INVALID_REQUEST다. */
  public static YearMonth parse(String value, YearMonth earliest, YearMonth current) {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw invalid(FieldErrorCode.INVALID_FORMAT, "yearMonth는 YYYY-MM 형식이어야 합니다.");
    }
    YearMonth month = YearMonth.parse(value);
    if (month.isAfter(current)) {
      throw invalid(FieldErrorCode.OUT_OF_RANGE, "현재 달보다 뒤의 달은 조회할 수 없습니다.");
    }
    if (month.isBefore(earliest)) {
      throw invalid(FieldErrorCode.OUT_OF_RANGE, "사무소 생성월보다 앞의 달은 조회할 수 없습니다.");
    }
    return month;
  }

  public static SettlementWindow window(Long officeId, YearMonth month, Instant now) {
    LocalDate firstDay = month.atDay(1);
    LocalDate lastDay = month.atEndOfMonth();
    LocalDate today = TimeRules.displayDate(now);
    return new SettlementWindow(
        officeId,
        TimeRules.startOfDisplayDay(firstDay),
        TimeRules.endOfDisplayDayExclusive(lastDay),
        firstDay,
        lastDay,
        today.isBefore(lastDay) ? today : lastDay);
  }

  private static BusinessException invalid(FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem("yearMonth", code, message)));
  }
}
