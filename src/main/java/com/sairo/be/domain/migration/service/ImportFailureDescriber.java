package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.dto.response.ImportRowResultsResponse.Issue;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ImportFailureDescriber {

  private static final Pattern ISSUE =
      Pattern.compile("^([A-Z][A-Z_]*)(?:[(]([A-Za-z]+)[)])?: (.*)$", Pattern.DOTALL);

  private static final Map<String, String> FIX =
      Map.ofEntries(
          Map.entry("REQUIRED_VALUE_MISSING", "칸이 비어 있습니다. 값을 입력해 주세요."),
          Map.entry("RESERVED_SOURCE_ID", "다른 번호로 바꾸거나 칸을 비워 주세요. 비우면 사이로가 번호를 붙입니다."),
          Map.entry("HTML_NOT_ALLOWED", "'<', '>' 같은 태그 문자를 지우고 일반 글자만 입력해 주세요."),
          Map.entry("VALUE_TOO_LONG", "글자 수를 줄여 주세요."),
          Map.entry("SENSITIVE_DATA_DETECTED", "주민등록번호·카드번호처럼 보이는 숫자를 지워 주세요. 사이로에는 저장하지 않습니다."),
          Map.entry("INVALID_PHONE_FORMAT", "010-1234-5678 형식의 번호로 고쳐 주세요."),
          Map.entry("INVALID_EMAIL_FORMAT", "name@example.com 형식으로 고쳐 주세요."),
          Map.entry("INVALID_DATE", "2026-01-31 형식의 실제 있는 날짜로 고쳐 주세요."),
          Map.entry("INVALID_NUMBER", "숫자만 입력해 주세요. 단위는 평, ㎡, 만원, 원만 붙일 수 있습니다."),
          Map.entry("OUT_OF_RANGE", "허용 범위 안의 값으로 고쳐 주세요."),
          Map.entry("MOVE_IN_CONFLICT", "입주일 협의 가능 여부를 아니오로 바꾸거나 입주 가능일을 비워 주세요."),
          Map.entry("SAVE_ERROR", "잠시 뒤 실패한 줄만 다시 처리해 주세요. 계속 실패하면 문의해 주세요."));

  private ImportFailureDescriber() {}

  public static List<Issue> describe(ImportTargetType type, String stored) {
    List<Issue> issues = new ArrayList<>();
    if (stored == null || stored.isBlank()) {
      return issues;
    }
    for (String part : stored.split(" / ")) {
      Matcher matcher = ISSUE.matcher(part.strip());
      if (!matcher.matches()) {
        issues.add(new Issue("UNKNOWN", null, null, "처리에 실패했습니다.", generalFix(), false));
        continue;
      }
      String code = matcher.group(1);
      String field = matcher.group(2);
      Optional<ImportTargetField> target =
          field == null ? Optional.empty() : ImportTargetFields.find(type, field);
      String label = target.map(ImportTargetField::label).orElse(null);
      String message = matcher.group(3);
      String fix = FIX.get(code);
      if (fix == null) {
        fix = generalFix();
      } else if (code.equals("REQUIRED_VALUE_MISSING") && label != null) {
        fix = "'" + label + "' " + fix;
      }
      issues.add(
          new Issue(
              code,
              target.map(ImportTargetField::field).orElse(null),
              label,
              message,
              fix,
              target.isPresent()));
    }
    return issues;
  }

  private static String generalFix() {
    return "값을 확인해 고친 뒤 다시 처리해 주세요. 계속 실패하면 문의해 주세요.";
  }
}
