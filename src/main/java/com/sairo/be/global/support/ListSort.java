package com.sairo.be.global.support;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.util.List;
import java.util.Map;

/** 목록 정렬 요청. "키,방향" 형식이며 허용된 키만 열 이름으로 바꾼다. */
public record ListSort(String column, boolean descending) {

  public static ListSort parse(String raw, Map<String, String> columnsByKey) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String[] parts = raw.strip().split(",", -1);
    String column = columnsByKey.get(parts[0].strip());
    boolean descending = false;
    if (parts.length > 2) {
      column = null;
    } else if (parts.length == 2) {
      String direction = parts[1].strip();
      if ("desc".equalsIgnoreCase(direction)) {
        descending = true;
      } else if (!"asc".equalsIgnoreCase(direction)) {
        column = null;
      }
    }
    if (column == null) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(new FieldErrorItem("sort", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 정렬입니다.")));
    }
    return new ListSort(column, descending);
  }

  /** 선택한 열을 먼저, 같은 값은 id 역순으로 안정적으로 정렬하는 ORDER BY 절 조각이다. */
  public String orderBy(String idColumn) {
    return column + (descending ? " DESC" : " ASC") + ", " + idColumn + " DESC";
  }
}
