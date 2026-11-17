package com.sairo.be.domain.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.migration.dto.response.ImportRowResultsResponse.Issue;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportFailureDescriberTest {

  @Test
  void 필드가_붙은_저장_형식을_항목_이름과_고치는_방법으로_바꾼다() {
    List<Issue> issues =
        ImportFailureDescriber.describe(
            ImportTargetType.PROPERTY,
            "INVALID_DATE(moveInDate): 날짜 형식을 해석할 수 없습니다. / INVALID_NUMBER(exclusiveAreaSqm): 숫자로 해석할 수 없습니다.");

    assertThat(issues).hasSize(2);
    assertThat(issues.get(0).fieldLabel()).isEqualTo("입주 가능일");
    assertThat(issues.get(0).fix()).contains("2026-01-31");
    assertThat(issues.get(0).correctable()).isTrue();
    assertThat(issues.get(1).fieldLabel()).isEqualTo("전용면적(㎡)");
  }

  @Test
  void 예전_형식과_알_수_없는_사유도_일반_안내로_돌려준다() {
    List<Issue> legacy =
        ImportFailureDescriber.describe(
            ImportTargetType.CUSTOMER, "REQUIRED_VALUE_MISSING: 필수 값이 비어 있습니다.");
    assertThat(legacy.get(0).field()).isNull();
    assertThat(legacy.get(0).correctable()).isFalse();
    assertThat(legacy.get(0).fix()).isNotBlank();

    List<Issue> unknown = ImportFailureDescriber.describe(ImportTargetType.CUSTOMER, "엉뚱한 문장");
    assertThat(unknown.get(0).code()).isEqualTo("UNKNOWN");
    assertThat(ImportFailureDescriber.describe(ImportTargetType.CUSTOMER, null)).isEmpty();
  }

  @Test
  void 저장_오류는_잠시_뒤_다시_처리하라고_안내한다() {
    List<Issue> issues =
        ImportFailureDescriber.describe(ImportTargetType.CUSTOMER, "SAVE_ERROR: 저장 중 오류가 발생했습니다.");
    assertThat(issues.get(0).fix()).contains("다시 처리");
  }

  @Test
  void CSV_셀은_수식을_무력화하고_쉼표와_따옴표를_감싼다() {
    assertThat(ImportRowResultService.csvCell("=1+1")).isEqualTo("'=1+1");
    assertThat(ImportRowResultService.csvCell("+cmd")).isEqualTo("'+cmd");
    assertThat(ImportRowResultService.csvCell("+8210")).isEqualTo("+8210");
    assertThat(ImportRowResultService.csvCell("-5")).isEqualTo("-5");
    assertThat(ImportRowResultService.csvCell("a,\"b\"")).isEqualTo("\"a,\"\"b\"\"\"");
    assertThat(ImportRowResultService.csvCell(null)).isEmpty();
  }
}
