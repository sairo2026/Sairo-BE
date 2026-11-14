package com.sairo.be.global.support;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record PageQuery(int page, int size) {

  public static final int DEFAULT_SIZE = 25;
  public static final Set<Integer> ALLOWED_SIZES = Set.of(25, 50, 100);

  public static PageQuery of(Integer page, Integer size) {
    int resolvedPage = page == null ? 0 : page;
    int resolvedSize = size == null ? DEFAULT_SIZE : size;
    List<FieldErrorItem> errors = new ArrayList<>();
    if (resolvedPage < 0) {
      errors.add(new FieldErrorItem("page", FieldErrorCode.OUT_OF_RANGE, "page는 0 이상이어야 합니다."));
    }
    if (!ALLOWED_SIZES.contains(resolvedSize)) {
      errors.add(
          new FieldErrorItem("size", FieldErrorCode.NOT_ALLOWED, "size는 25, 50, 100 중 하나여야 합니다."));
    }
    if (!errors.isEmpty()) {
      throw BusinessException.withFieldErrors(ErrorCode.INVALID_REQUEST, errors);
    }
    return new PageQuery(resolvedPage, resolvedSize);
  }

  public Pageable toPageable(Sort sort) {
    return PageRequest.of(page, size, sort);
  }
}
