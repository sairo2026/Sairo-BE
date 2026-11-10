package com.sairo.be.global.support;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class StatusFilter {

  private StatusFilter() {}

  public static <E extends Enum<E>> Set<E> parse(String raw, Class<E> type, Set<E> defaults) {
    if (raw == null || raw.isBlank()) {
      return defaults;
    }
    Set<E> parsed = EnumSet.noneOf(type);
    List<FieldErrorItem> errors = new ArrayList<>();
    for (String token : raw.split(",")) {
      try {
        parsed.add(Enum.valueOf(type, token.strip()));
      } catch (IllegalArgumentException e) {
        errors.add(
            new FieldErrorItem("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 status 값입니다."));
        break;
      }
    }
    if (!errors.isEmpty()) {
      throw BusinessException.withFieldErrors(ErrorCode.INVALID_REQUEST, errors);
    }
    return parsed;
  }
}
