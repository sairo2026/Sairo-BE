package com.sairo.be.domain.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.migration.entity.ImportTargetType;
import com.sairo.be.domain.migration.service.HeaderMappingSuggester.Suggestion;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RuleBasedHeaderMappingSuggesterTest {

  private final RuleBasedHeaderMappingSuggester suggester = new RuleBasedHeaderMappingSuggester();

  private Map<String, Suggestion> suggest(ImportTargetType type, String... headers) {
    return suggester.suggest(type, List.of(headers)).stream()
        .collect(Collectors.toMap(Suggestion::sourceColumn, Function.identity()));
  }

  @Test
  void 고객_머리글을_동의어로_찾아_신뢰도와_함께_제안한다() {
    Map<String, Suggestion> result =
        suggest(ImportTargetType.CUSTOMER, "고객번호", "성 명", "휴대폰 번호", "E-mail", "비고", "임의 열");

    assertThat(result.get("고객번호").targetField()).isEqualTo("customerSourceId");
    assertThat(result.get("고객번호").confidence()).isEqualTo(0.95);
    assertThat(result.get("성 명").targetField()).isEqualTo("customerName");
    assertThat(result.get("휴대폰 번호").targetField()).isEqualTo("customerPhone");
    assertThat(result.get("E-mail").targetField()).isEqualTo("customerEmail");
    assertThat(result.get("비고").targetField()).isEqualTo("customerMemo");
    assertThat(result.get("비고").confidence()).isEqualTo(0.6);
    assertThat(result).doesNotContainKey("임의 열");
  }

  @Test
  void 같은_대상_항목을_두_열에_제안하지_않고_더_확실한_열을_고른다() {
    Map<String, Suggestion> result = suggest(ImportTargetType.CUSTOMER, "연락처", "휴대폰");

    assertThat(result).hasSize(1);
    assertThat(result.get("연락처").targetField()).isEqualTo("customerPhone");
  }

  @Test
  void 보조_연락처와_연락처를_구분한다() {
    Map<String, Suggestion> result = suggest(ImportTargetType.CUSTOMER, "연락처", "보조 연락처");

    assertThat(result.get("연락처").targetField()).isEqualTo("customerPhone");
    assertThat(result.get("보조 연락처").targetField()).isEqualTo("customerSecondaryPhone");
  }

  @Test
  void 매물_머리글은_단위_표기가_붙어도_찾는다() {
    Map<String, Suggestion> result =
        suggest(
            ImportTargetType.PROPERTY,
            "매물번호",
            "매물명",
            "주소",
            "상세주소",
            "전용면적(㎡)",
            "공급면적(평)",
            "관리비(원)",
            "입주가능일",
            "점유상태");

    assertThat(result.get("매물번호").targetField()).isEqualTo("propertySourceId");
    assertThat(result.get("주소").targetField()).isEqualTo("propertyAddress");
    assertThat(result.get("상세주소").targetField()).isEqualTo("propertyAddressDetail");
    assertThat(result.get("전용면적(㎡)").targetField()).isEqualTo("exclusiveAreaSqm");
    assertThat(result.get("공급면적(평)").targetField()).isEqualTo("supplyAreaSqm");
    assertThat(result.get("관리비(원)").targetField()).isEqualTo("maintenanceFeeAmount");
    assertThat(result.get("입주가능일").targetField()).isEqualTo("moveInDate");
    assertThat(result.get("점유상태").targetField()).isEqualTo("occupancyStatus");
  }

  @Test
  void 계약은_매핑할_수_있는_항목이_없어_제안하지_않는다() {
    assertThat(suggester.suggest(ImportTargetType.CONTRACT, List.of("계약번호", "성명"))).isEmpty();
  }

  @Test
  void 목록은_원본_열_순서를_따른다() {
    List<Suggestion> list =
        suggester.suggest(ImportTargetType.CUSTOMER, List.of("이메일", "고객번호", "성명"));

    assertThat(list).extracting(Suggestion::sourceColumn).containsExactly("이메일", "고객번호", "성명");
  }
}
