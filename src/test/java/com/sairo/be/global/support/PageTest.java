package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PageTest {

  @Test
  void 기본값은_0페이지_25건이다() {
    PageQuery query = PageQuery.of(null, null);

    assertThat(query.page()).isZero();
    assertThat(query.size()).isEqualTo(25);
  }

  @ParameterizedTest
  @ValueSource(ints = {25, 50, 100})
  void 허용된_크기를_받는다(int size) {
    assertThat(PageQuery.of(2, size).toPageable(Sort.unsorted()).getPageSize()).isEqualTo(size);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 10, 26, 101, -25})
  void 허용되지_않은_크기는_INVALID_REQUEST다(int size) {
    assertThatThrownBy(() -> PageQuery.of(0, size))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
              assertThat(exception.getFieldErrors().get(0).field()).isEqualTo("size");
            });
  }

  @Test
  void 음수_페이지는_INVALID_REQUEST다() {
    assertThatThrownBy(() -> PageQuery.of(-1, 25))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getFieldErrors().get(0).field()).isEqualTo("page"));
  }

  @Test
  void 응답은_items_page_size_totalElements를_담는다() throws Exception {
    PageResponse<String> response =
        PageResponse.from(
            new PageImpl<>(List.of(1, 2), PageRequest.of(1, 25), 27), String::valueOf);

    JsonNode json =
        JsonMapper.builder()
            .build()
            .readTree(JsonMapper.builder().build().writeValueAsString(response));

    assertThat(json.get("items")).hasSize(2);
    assertThat(json.get("items").get(0).asString()).isEqualTo("1");
    assertThat(json.get("page").asInt()).isEqualTo(1);
    assertThat(json.get("size").asInt()).isEqualTo(25);
    assertThat(json.get("totalElements").asLong()).isEqualTo(27L);
    assertThat(json.propertyNames())
        .containsExactlyInAnyOrder("items", "page", "size", "totalElements");
  }

  @Test
  void 빈_결과는_items가_빈_배열이고_totalElements가_0이다() throws Exception {
    PageResponse<String> response = PageResponse.of(List.of(), 0, 25, 0);

    JsonNode json =
        JsonMapper.builder()
            .build()
            .readTree(JsonMapper.builder().build().writeValueAsString(response));

    assertThat(json.get("items").isArray()).isTrue();
    assertThat(json.get("items")).isEmpty();
    assertThat(json.get("totalElements").asLong()).isZero();
  }
}
