package com.sairo.be.domain.property.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@IntegrationTest
class PropertySeedTest extends AuthIntegrationTestBase {

  @Test
  void 기본_카테고리_열_개가_코드와_이름으로_시드된다() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT code, name, is_system, active, office_id FROM property_category ORDER BY id");
    assertThat(rows).hasSize(10);
    assertThat(rows)
        .extracting(row -> row.get("code"))
        .containsExactly(
            "COMMERCIAL_BUILDING",
            "LAND",
            "RETAIL_OFFICE",
            "FACTORY_WAREHOUSE",
            "DETACHED_MULTI",
            "APARTMENT",
            "OFFICETEL",
            "ROWHOUSE_MULTI",
            "LODGING",
            "PRESALE_RIGHT");
    assertThat(rows)
        .extracting(row -> row.get("name"))
        .containsExactly(
            "상업용건물", "토지", "상가·사무실", "공장·창고", "단독·다가구", "아파트", "오피스텔", "연립·다세대", "숙박시설", "분양권");
    assertThat(rows).allSatisfy(row -> assertThat(row.get("office_id")).isNull());
    assertThat(rows).allSatisfy(row -> assertThat(row.get("is_system")).isEqualTo(true));
  }

  @Test
  void 카테고리별_기본_전용_필드_수와_숫자_규칙을_지킨다() {
    Map<String, Integer> expected =
        Map.of(
            "COMMERCIAL_BUILDING", 6,
            "LAND", 4,
            "RETAIL_OFFICE", 5,
            "FACTORY_WAREHOUSE", 4,
            "DETACHED_MULTI", 7,
            "APARTMENT", 7,
            "OFFICETEL", 7,
            "ROWHOUSE_MULTI", 8,
            "LODGING", 5,
            "PRESALE_RIGHT", 8);
    expected.forEach(
        (code, count) ->
            assertThat(
                    jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM property_custom_field_definition d JOIN"
                            + " property_category c ON c.id = d.category_id WHERE c.code = ?",
                        Integer.class,
                        code))
                .as(code)
                .isEqualTo(count));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM property_custom_field_definition WHERE field_type = 'NUMBER'"
                    + " AND min_value IS NULL",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT field_name, unit FROM property_custom_field_definition WHERE field_type ="
                    + " 'NUMBER' AND min_value IS NULL"))
        .containsEntry("field_name", "프리미엄")
        .containsEntry("unit", "원");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT max_value FROM property_custom_field_definition WHERE field_name = '전용률'",
                Integer.class))
        .isEqualTo(100);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM property_custom_field_definition WHERE number_kind ="
                    + " 'DECIMAL'",
                Integer.class))
        .isEqualTo(4);
  }
}
