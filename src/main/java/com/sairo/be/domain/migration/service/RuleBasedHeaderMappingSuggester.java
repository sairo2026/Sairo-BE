package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.entity.ImportTargetType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class RuleBasedHeaderMappingSuggester implements HeaderMappingSuggester {

  private static final double MIN_CONFIDENCE = 0.5;
  private static final List<String> UNIT_SUFFIXES = List.of("만원", "원", "㎡", "평", "m2");

  private static final Map<String, List<String>> STRONG = new LinkedHashMap<>();
  private static final Map<String, List<String>> WEAK = new LinkedHashMap<>();

  static {
    strong(
        ImportTargetFields.CUSTOMER_SOURCE_ID,
        "고객번호",
        "고객id",
        "고객코드",
        "고객일련번호",
        "회원번호",
        "고객no",
        "customerid",
        "customerno");
    weak(ImportTargetFields.CUSTOMER_SOURCE_ID, "번호", "id", "no", "seq", "일련번호", "관리번호", "코드");
    strong(ImportTargetFields.CUSTOMER_NAME, "고객명", "고객이름", "고객성명", "성명", "법인명", "customername");
    weak(ImportTargetFields.CUSTOMER_NAME, "이름", "name", "상호", "성함");
    strong(ImportTargetFields.CUSTOMER_IS_CORPORATION, "법인여부", "법인구분", "iscorporation");
    weak(ImportTargetFields.CUSTOMER_IS_CORPORATION, "법인", "개인법인", "구분");
    strong(
        ImportTargetFields.CUSTOMER_PHONE,
        "연락처",
        "전화번호",
        "휴대폰",
        "휴대전화",
        "핸드폰",
        "휴대폰번호",
        "핸드폰번호",
        "연락처1",
        "mobile",
        "phone");
    weak(ImportTargetFields.CUSTOMER_PHONE, "전화", "tel", "hp");
    strong(
        ImportTargetFields.CUSTOMER_SECONDARY_PHONE,
        "보조연락처",
        "보조전화",
        "추가연락처",
        "연락처2",
        "자택번호",
        "집전화",
        "전화번호2",
        "secondaryphone");
    weak(ImportTargetFields.CUSTOMER_SECONDARY_PHONE, "기타연락처", "자택");
    strong(ImportTargetFields.CUSTOMER_EMAIL, "이메일", "이메일주소", "email", "메일주소");
    weak(ImportTargetFields.CUSTOMER_EMAIL, "메일");
    strong(ImportTargetFields.CUSTOMER_MEMO, "고객메모", "특이사항", "customermemo");
    weak(ImportTargetFields.CUSTOMER_MEMO, "메모", "비고", "참고", "note", "memo");

    strong(
        ImportTargetFields.PROPERTY_SOURCE_ID,
        "매물번호",
        "매물id",
        "매물코드",
        "물건번호",
        "물건id",
        "매물no",
        "propertyid",
        "propertyno");
    weak(ImportTargetFields.PROPERTY_SOURCE_ID, "번호", "id", "no", "seq", "일련번호", "관리번호", "코드");
    strong(ImportTargetFields.PROPERTY_NAME, "매물명", "물건명", "건물명", "단지명", "매물이름", "propertyname");
    weak(ImportTargetFields.PROPERTY_NAME, "이름", "name", "명칭", "상호");
    strong(
        ImportTargetFields.PROPERTY_ADDRESS,
        "주소",
        "기본주소",
        "소재지",
        "도로명주소",
        "지번주소",
        "소재지주소",
        "address");
    weak(ImportTargetFields.PROPERTY_ADDRESS, "위치");
    strong(ImportTargetFields.PROPERTY_ADDRESS_DETAIL, "상세주소", "동호수", "addressdetail");
    weak(ImportTargetFields.PROPERTY_ADDRESS_DETAIL, "호수", "동호", "호", "상세");
    strong(ImportTargetFields.EXCLUSIVE_AREA, "전용면적", "전용면적㎡", "exclusivearea");
    weak(ImportTargetFields.EXCLUSIVE_AREA, "전용", "전용평수");
    strong(ImportTargetFields.SUPPLY_AREA, "공급면적", "분양면적", "공급면적㎡", "supplyarea");
    weak(ImportTargetFields.SUPPLY_AREA, "공급");
    strong(ImportTargetFields.LAND_AREA, "대지면적", "토지면적", "landarea");
    weak(ImportTargetFields.LAND_AREA, "대지", "토지", "대지지분");
    strong(ImportTargetFields.GROSS_FLOOR_AREA, "연면적", "연면적㎡", "건물면적");
    strong(ImportTargetFields.MOVE_IN_DATE, "입주가능일", "입주일", "입주예정일", "입주가능시기", "입주시기", "movein");
    strong(ImportTargetFields.MOVE_IN_NEGOTIABLE, "입주협의", "입주일협의", "입주협의가능", "협의여부");
    strong(ImportTargetFields.MAINTENANCE_FEE_AMOUNT, "관리비", "관리비금액", "월관리비");
    strong(ImportTargetFields.MAINTENANCE_FEE_ITEMS, "관리비포함항목", "관리비항목", "관리비포함", "포함항목");
    strong(ImportTargetFields.OCCUPANCY_STATUS, "점유상태", "거주상태", "공실여부", "입주상태", "점유");
    strong(
        ImportTargetFields.PROPERTY_DESCRIPTION, "매물설명", "상세설명", "설명", "소개", "propertydescription");
    weak(ImportTargetFields.PROPERTY_DESCRIPTION, "특징");
    strong(ImportTargetFields.PROPERTY_MEMO, "매물메모", "특이사항", "propertymemo");
    weak(ImportTargetFields.PROPERTY_MEMO, "메모", "비고", "참고", "memo", "note");
  }

  private record Candidate(int index, String field, double confidence) {}

  @Override
  public List<Suggestion> suggest(ImportTargetType targetType, List<String> headers) {
    List<ImportTargetField> fields = ImportTargetFields.forType(targetType);
    List<Candidate> candidates = new ArrayList<>();
    for (int index = 0; index < headers.size(); index++) {
      String normalized = normalize(headers.get(index));
      if (normalized.isEmpty()) {
        continue;
      }
      for (ImportTargetField field : fields) {
        double confidence = score(normalized, field.field());
        if (confidence >= MIN_CONFIDENCE) {
          candidates.add(new Candidate(index, field.field(), confidence));
        }
      }
    }
    candidates.sort(
        Comparator.comparingDouble(Candidate::confidence)
            .reversed()
            .thenComparingInt(Candidate::index));
    Set<Integer> usedColumns = new HashSet<>();
    Set<String> usedFields = new HashSet<>();
    Map<Integer, Suggestion> picked = new LinkedHashMap<>();
    for (Candidate candidate : candidates) {
      if (usedColumns.contains(candidate.index()) || usedFields.contains(candidate.field())) {
        continue;
      }
      usedColumns.add(candidate.index());
      usedFields.add(candidate.field());
      picked.put(
          candidate.index(),
          new Suggestion(
              headers.get(candidate.index()), candidate.field(), candidate.confidence()));
    }
    return picked.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue)
        .toList();
  }

  private static double score(String header, String field) {
    List<String> strong = STRONG.getOrDefault(field, List.of());
    List<String> weak = WEAK.getOrDefault(field, List.of());
    if (strong.contains(header)) {
      return 0.95;
    }
    String stripped = stripUnit(header);
    if (!stripped.equals(header) && strong.contains(stripped)) {
      return 0.9;
    }
    if (weak.contains(header) || weak.contains(stripped)) {
      return 0.6;
    }
    double best = 0;
    for (String synonym : strong) {
      if (synonym.length() >= 3 && header.contains(synonym)) {
        best = Math.max(best, 0.7);
      } else if (synonym.length() >= 3 && similarity(header, synonym) >= 0.8) {
        best = Math.max(best, 0.6);
      }
    }
    return best;
  }

  static String normalize(String value) {
    StringBuilder builder = new StringBuilder();
    for (char c : value.toCharArray()) {
      if (Character.isLetterOrDigit(c) || c == '㎡') {
        builder.append(Character.toLowerCase(c));
      }
    }
    return builder.toString();
  }

  private static String stripUnit(String header) {
    for (String suffix : UNIT_SUFFIXES) {
      if (header.endsWith(suffix) && header.length() > suffix.length()) {
        return header.substring(0, header.length() - suffix.length());
      }
    }
    return header;
  }

  private static double similarity(String a, String b) {
    int distance = levenshtein(a, b);
    return 1.0 - (double) distance / Math.max(a.length(), b.length());
  }

  private static int levenshtein(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        current[j] =
            Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }

  private static void strong(String field, String... synonyms) {
    STRONG.put(field, normalized(synonyms));
  }

  private static void weak(String field, String... synonyms) {
    WEAK.put(field, normalized(synonyms));
  }

  private static List<String> normalized(String... synonyms) {
    List<String> result = new ArrayList<>();
    for (String synonym : synonyms) {
      result.add(normalize(synonym));
    }
    return result;
  }
}
