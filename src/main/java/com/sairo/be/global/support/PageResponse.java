package com.sairo.be.global.support;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

@Schema(description = "목록 응답의 공통 형식이다.")
public record PageResponse<T>(
    @Schema(description = "현재 페이지의 항목") List<T> items,
    @Schema(description = "0부터 시작하는 페이지 번호", example = "0") int page,
    @Schema(description = "페이지 크기", example = "25") int size,
    @Schema(description = "전체 항목 수", example = "0") long totalElements) {

  public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
    return new PageResponse<>(List.copyOf(items), page, size, totalElements);
  }

  public static <S, T> PageResponse<T> from(Page<S> source, Function<S, T> mapper) {
    return of(
        source.getContent().stream().map(mapper).toList(),
        source.getNumber(),
        source.getSize(),
        source.getTotalElements());
  }
}
