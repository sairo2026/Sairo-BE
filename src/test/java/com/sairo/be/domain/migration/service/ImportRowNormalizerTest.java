package com.sairo.be.domain.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.migration.entity.ImportTargetType;
import com.sairo.be.domain.migration.service.NormalizedRow.RowWarning;
import com.sairo.be.domain.migration.service.NormalizedRow.Severity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportRowNormalizerTest {

  private final ImportRowNormalizer normalizer = new ImportRowNormalizer();

  private static Map<String, String> raw(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return map;
  }

  private NormalizedRow property(String... pairs) {
    return normalizer.normalize(ImportTargetType.PROPERTY, raw(pairs), Map.of());
  }

  private static RowWarning warning(NormalizedRow row, String code) {
    return row.warnings().stream()
        .filter(item -> item.code().equals(code))
        .findFirst()
        .orElseThrow(() -> new AssertionError(code + " 경고가 없다: " + row.warnings()));
  }

  @Test
  void 평_단위_면적은_제곱미터로_바꾸고_변환_내역을_남긴다() {
    NormalizedRow row =
        property(
            ImportTargetFields.PROPERTY_SOURCE_ID, "P1",
            ImportTargetFields.PROPERTY_NAME, "역삼 빌딩",
            ImportTargetFields.EXCLUSIVE_AREA, "30평");

    assertThat(row.values().get(ImportTargetFields.EXCLUSIVE_AREA))
        .isEqualTo(new BigDecimal("99.17"));
    assertThat(row.unitConversions()).hasSize(1);
    assertThat(row.unitConversions().get(0).ruleCode()).isEqualTo("PYEONG_TO_SQM");
    assertThat(row.unitConversions().get(0).sourceValue()).isEqualTo("30평");
    assertThat(row.hasError()).isFalse();
  }

  @Test
  void 열_이름이_평이면_단위_없는_숫자도_평으로_본다() {
    NormalizedRow row =
        normalizer.normalize(
            ImportTargetType.PROPERTY,
            raw(
                ImportTargetFields.PROPERTY_SOURCE_ID, "P1",
                ImportTargetFields.PROPERTY_NAME, "a",
                ImportTargetFields.SUPPLY_AREA, "10"),
            Map.of(ImportTargetFields.SUPPLY_AREA, "공급면적(평)"));

    assertThat(row.values().get(ImportTargetFields.SUPPLY_AREA)).isEqualTo(new BigDecimal("33.06"));
    assertThat(row.unitConversions()).hasSize(1);
  }

  @Test
  void 제곱미터_표기와_소수_셋째_자리는_그대로_읽거나_반올림하고_경고한다() {
    NormalizedRow row =
        property(
            ImportTargetFields.PROPERTY_SOURCE_ID, "P1",
            ImportTargetFields.PROPERTY_NAME, "a",
            ImportTargetFields.EXCLUSIVE_AREA, "84.987㎡",
            ImportTargetFields.LAND_AREA, "1,200 m2");

    assertThat(row.values().get(ImportTargetFields.EXCLUSIVE_AREA))
        .isEqualTo(new BigDecimal("84.99"));
    assertThat(warning(row, "AREA_ROUNDED").severity()).isEqualTo(Severity.WARNING);
    assertThat(row.values().get(ImportTargetFields.LAND_AREA)).isEqualTo(new BigDecimal("1200"));
    assertThat(row.unitConversions()).isEmpty();
  }

  @Test
  void 숫자로_읽을_수_없는_면적과_0_면적은_오류다() {
    NormalizedRow row =
        property(
            ImportTargetFields.PROPERTY_SOURCE_ID, "P1",
            ImportTargetFields.PROPERTY_NAME, "a",
            ImportTargetFields.EXCLUSIVE_AREA, "넓음",
            ImportTargetFields.LAND_AREA, "0");

    assertThat(warning(row, "INVALID_NUMBER").severity()).isEqualTo(Severity.ERROR);
    assertThat(warning(row, "OUT_OF_RANGE").field()).isEqualTo(ImportTargetFields.LAND_AREA);
    assertThat(row.hasError()).isTrue();
  }

  @Test
  void 관리비는_만원_단위를_원으로_바꾼다() {
    NormalizedRow row =
        property(
            ImportTargetFields.PROPERTY_SOURCE_ID, "P1",
            ImportTargetFields.PROPERTY_NAME, "a",
            ImportTargetFields.MAINTENANCE_FEE_AMOUNT, "5만원");

    assertThat(row.values().get(ImportTargetFields.MAINTENANCE_FEE_AMOUNT)).isEqualTo(50_000L);
    assertThat(row.unitConversions().get(0).ruleCode()).isEqualTo("MAN_WON_TO_WON");

    NormalizedRow won =
        property(
            ImportTargetFields.PROPERTY_SOURCE_ID, "P2",
            ImportTargetFields.PROPERTY_NAME, "a",
            ImportTargetFields.MAINTENANCE_FEE_AMOUNT, "120,000원");
    assertThat(won.values().get(ImportTargetFields.MAINTENANCE_FEE_AMOUNT)).isEqualTo(120_000L);
    assertThat(won.unitConversions()).isEmpty();
  }

  @Test
  void 날짜는_여러_표기를_읽고_없는_날짜는_오류다() {
    for (String text :
        new String[] {"2026-03-05", "2026.3.5", "2026/03/05", "20260305", "2026년 3월 5일"}) {
      NormalizedRow row =
          property(
              ImportTargetFields.PROPERTY_SOURCE_ID, "P1",
              ImportTargetFields.PROPERTY_NAME, "a",
              ImportTargetFields.MOVE_IN_DATE, text);
      assertThat(row.values().get(ImportTargetFields.MOVE_IN_DATE))
          .as(text)
          .isEqualTo(LocalDate.of(2026, 3, 5));
    }
    NormalizedRow bad =
        property(
            ImportTargetFields.PROPERTY_SOURCE_ID, "P1",
            ImportTargetFields.PROPERTY_NAME, "a",
            ImportTargetFields.MOVE_IN_DATE, "2026-02-30");
    assertThat(warning(bad, "INVALID_DATE").severity()).isEqualTo(Severity.ERROR);
  }

  @Test
  void 입주일_협의와_입주_가능일을_함께_쓰면_오류다() {
    NormalizedRow row =
        property(
            ImportTargetFields.PROPERTY_SOURCE_ID, "P1",
            ImportTargetFields.PROPERTY_NAME, "a",
            ImportTargetFields.MOVE_IN_DATE, "2026-03-05",
            ImportTargetFields.MOVE_IN_NEGOTIABLE, "Y");

    assertThat(warning(row, "MOVE_IN_CONFLICT").severity()).isEqualTo(Severity.ERROR);
  }

  @Test
  void 점유상태는_한글_표기를_코드로_바꾸고_모르는_값은_미상과_경고로_처리한다() {
    NormalizedRow vacant =
        property(
            ImportTargetFields.PROPERTY_SOURCE_ID, "P1",
            ImportTargetFields.PROPERTY_NAME, "a",
            ImportTargetFields.OCCUPANCY_STATUS, "공실");
    assertThat(vacant.values().get(ImportTargetFields.OCCUPANCY_STATUS)).isEqualTo("VACANT");

    NormalizedRow unknown =
        property(
            ImportTargetFields.PROPERTY_SOURCE_ID, "P2",
            ImportTargetFields.PROPERTY_NAME, "a",
            ImportTargetFields.OCCUPANCY_STATUS, "이상한 값");
    assertThat(unknown.values().get(ImportTargetFields.OCCUPANCY_STATUS)).isEqualTo("UNKNOWN");
    assertThat(warning(unknown, "UNKNOWN_OCCUPANCY_VALUE").severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void 필수_값이_비면_오류이고_고객_연락처_형식은_경고다() {
    NormalizedRow row =
        normalizer.normalize(
            ImportTargetType.CUSTOMER,
            raw(
                ImportTargetFields.CUSTOMER_SOURCE_ID, " ",
                ImportTargetFields.CUSTOMER_NAME, "홍길동",
                ImportTargetFields.CUSTOMER_PHONE, "02-123",
                ImportTargetFields.CUSTOMER_IS_CORPORATION, "법인"),
            Map.of());

    assertThat(warning(row, "REQUIRED_VALUE_MISSING").field())
        .isEqualTo(ImportTargetFields.CUSTOMER_SOURCE_ID);
    assertThat(warning(row, "INVALID_PHONE_FORMAT").severity()).isEqualTo(Severity.WARNING);
    assertThat(row.values().get(ImportTargetFields.CUSTOMER_IS_CORPORATION)).isEqualTo(true);
    assertThat(row.values().get(ImportTargetFields.CUSTOMER_PHONE)).isEqualTo("02-123");
    assertThat(row.sourceId()).isNull();
  }

  @Test
  void 휴대폰_번호는_하이픈_형식으로_정규화한다() {
    NormalizedRow row =
        normalizer.normalize(
            ImportTargetType.CUSTOMER,
            raw(
                ImportTargetFields.CUSTOMER_SOURCE_ID, "C1",
                ImportTargetFields.CUSTOMER_NAME, "홍길동",
                ImportTargetFields.CUSTOMER_PHONE, "01012345678"),
            Map.of());

    assertThat(row.values().get(ImportTargetFields.CUSTOMER_PHONE)).isEqualTo("010-1234-5678");
    assertThat(row.hasError()).isFalse();
    assertThat(row.warnings()).isEmpty();
  }

  @Test
  void 이메일_형식_민감_번호_HTML은_저장_때_실패하므로_미리보기에서_오류다() {
    NormalizedRow row =
        normalizer.normalize(
            ImportTargetType.CUSTOMER,
            raw(
                ImportTargetFields.CUSTOMER_SOURCE_ID, "C1",
                ImportTargetFields.CUSTOMER_NAME, "<b>홍길동</b>",
                ImportTargetFields.CUSTOMER_EMAIL, "abc",
                ImportTargetFields.CUSTOMER_MEMO, "주민번호 900101-1234567"),
            Map.of());

    assertThat(warning(row, "HTML_NOT_ALLOWED").field())
        .isEqualTo(ImportTargetFields.CUSTOMER_NAME);
    assertThat(warning(row, "INVALID_EMAIL_FORMAT").severity()).isEqualTo(Severity.ERROR);
    assertThat(warning(row, "SENSITIVE_DATA_DETECTED").field())
        .isEqualTo(ImportTargetFields.CUSTOMER_MEMO);
    assertThat(row.errorSummary()).contains("HTML_NOT_ALLOWED").contains("INVALID_EMAIL_FORMAT");
  }

  @Test
  void 너무_긴_값은_오류다() {
    NormalizedRow row =
        normalizer.normalize(
            ImportTargetType.CUSTOMER,
            raw(
                ImportTargetFields.CUSTOMER_SOURCE_ID,
                "C1",
                ImportTargetFields.CUSTOMER_NAME,
                "가".repeat(101)),
            Map.of());

    assertThat(warning(row, "VALUE_TOO_LONG").severity()).isEqualTo(Severity.ERROR);
  }

  @Test
  void 매핑되지_않은_항목은_정규화_값에_넣지_않고_빈_값은_null로_둔다() {
    NormalizedRow row =
        normalizer.normalize(
            ImportTargetType.CUSTOMER,
            raw(
                ImportTargetFields.CUSTOMER_SOURCE_ID, "C1",
                ImportTargetFields.CUSTOMER_NAME, "홍길동",
                ImportTargetFields.CUSTOMER_MEMO, " "),
            Map.of());

    assertThat(row.values())
        .containsOnlyKeys(
            ImportTargetFields.CUSTOMER_SOURCE_ID,
            ImportTargetFields.CUSTOMER_NAME,
            ImportTargetFields.CUSTOMER_MEMO);
    assertThat(row.values().get(ImportTargetFields.CUSTOMER_MEMO)).isNull();
  }
}
