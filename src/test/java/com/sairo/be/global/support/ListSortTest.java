package com.sairo.be.global.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ListSortTest {

  private static final Map<String, String> COLUMNS =
      Map.of("name", "p.name", "updatedAt", "p.updated_at");

  @Test
  void 값이_없으면_기본_정렬을_쓰도록_null을_돌려준다() {
    assertThat(ListSort.parse(null, COLUMNS)).isNull();
    assertThat(ListSort.parse("  ", COLUMNS)).isNull();
  }

  @Test
  void 방향을_생략하면_오름차순이고_desc는_내림차순이다() {
    assertThat(ListSort.parse("name", COLUMNS).orderBy("p.id")).isEqualTo("p.name ASC, p.id DESC");
    assertThat(ListSort.parse("updatedAt,DESC", COLUMNS).orderBy("p.id"))
        .isEqualTo("p.updated_at DESC, p.id DESC");
  }

  @Test
  void 허용하지_않는_키와_방향은_거절한다() {
    assertThatThrownBy(() -> ListSort.parse("password", COLUMNS))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> ListSort.parse("name,up", COLUMNS))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> ListSort.parse("name,asc,desc", COLUMNS))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> ListSort.parse("name; DROP TABLE x", COLUMNS))
        .isInstanceOf(BusinessException.class);
  }
}
