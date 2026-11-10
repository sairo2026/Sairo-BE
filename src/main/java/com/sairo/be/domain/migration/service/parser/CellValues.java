package com.sairo.be.domain.migration.service.parser;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.apache.poi.ss.usermodel.DateUtil;

final class CellValues {

  private CellValues() {}

  static String plain(double value) {
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return Long.toString((long) value);
    }
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

  static String date(double value) {
    if (!DateUtil.isValidExcelDate(value)) {
      return plain(value);
    }
    LocalDateTime dateTime = DateUtil.getLocalDateTime(value);
    if (dateTime.toLocalTime().equals(LocalTime.MIDNIGHT)) {
      return dateTime.toLocalDate().toString();
    }
    return dateTime.toLocalDate() + " " + dateTime.toLocalTime().withNano(0);
  }
}
