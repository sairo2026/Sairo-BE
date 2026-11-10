package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.customer.service.SensitiveDataDetector;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import com.sairo.be.domain.migration.service.NormalizedRow.RowWarning;
import com.sairo.be.domain.migration.service.NormalizedRow.Severity;
import com.sairo.be.domain.migration.service.NormalizedRow.UnitConversion;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.PhoneNumbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ImportRowNormalizer {

  public static final String PYEONG_TO_SQM = "PYEONG_TO_SQM";
  public static final String MAN_WON_TO_WON = "MAN_WON_TO_WON";

  private static final BigDecimal SQM_PER_PYEONG = new BigDecimal("3.305785");
  private static final BigDecimal MAN_WON = BigDecimal.valueOf(10_000);
  private static final BigDecimal AREA_LIMIT = new BigDecimal("9999999999.99");
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Pattern AREA =
      Pattern.compile("^(-?\\d+(?:\\.\\d+)?)(평|㎡|m2|m²|sqm|py)?$", Pattern.CASE_INSENSITIVE);
  private static final Pattern MONEY = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)(만원|만|원)?$");
  private static final Pattern DATE_PARTS =
      Pattern.compile("^(\\d{4})\\s*[-./년]\\s*(\\d{1,2})\\s*[-./월]\\s*(\\d{1,2})\\s*일?$");
  private static final Pattern DATE_COMPACT = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");

  private static final Map<String, Integer> MAX_LENGTH =
      Map.ofEntries(
          Map.entry(ImportTargetFields.CUSTOMER_SOURCE_ID, 200),
          Map.entry(ImportTargetFields.PROPERTY_SOURCE_ID, 200),
          Map.entry(ImportTargetFields.CUSTOMER_NAME, 100),
          Map.entry(ImportTargetFields.CUSTOMER_PHONE, 30),
          Map.entry(ImportTargetFields.CUSTOMER_SECONDARY_PHONE, 30),
          Map.entry(ImportTargetFields.CUSTOMER_EMAIL, 254),
          Map.entry(ImportTargetFields.CUSTOMER_MEMO, 10_000),
          Map.entry(ImportTargetFields.PROPERTY_NAME, 100),
          Map.entry(ImportTargetFields.PROPERTY_ADDRESS, 300),
          Map.entry(ImportTargetFields.PROPERTY_ADDRESS_DETAIL, 100),
          Map.entry(ImportTargetFields.MAINTENANCE_FEE_ITEMS, 500),
          Map.entry(ImportTargetFields.PROPERTY_DESCRIPTION, 10_000),
          Map.entry(ImportTargetFields.PROPERTY_MEMO, 10_000));

  private static final Set<String> SENSITIVE_CHECKED =
      Set.of(
          ImportTargetFields.CUSTOMER_NAME,
          ImportTargetFields.CUSTOMER_PHONE,
          ImportTargetFields.CUSTOMER_SECONDARY_PHONE,
          ImportTargetFields.CUSTOMER_EMAIL,
          ImportTargetFields.CUSTOMER_MEMO);

  private static final Set<String> TRUE_VALUES =
      Set.of("true", "t", "y", "yes", "1", "예", "네", "맞음", "o", "법인", "가능");
  private static final Set<String> FALSE_VALUES =
      Set.of("false", "f", "n", "no", "0", "아니오", "아니요", "x", "개인", "불가");

  public NormalizedRow normalize(
      ImportTargetType type, Map<String, String> rawByField, Map<String, String> columnByField) {
    Map<String, Object> values = new LinkedHashMap<>();
    List<UnitConversion> conversions = new ArrayList<>();
    List<RowWarning> warnings = new ArrayList<>();
    String sourceIdField = ImportTargetFields.sourceIdField(type);
    String nameField = ImportTargetFields.nameField(type);
    for (ImportTargetField target : ImportTargetFields.forType(type)) {
      String field = target.field();
      if (!rawByField.containsKey(field)) {
        continue;
      }
      String raw = clean(rawByField.get(field));
      if (raw == null) {
        values.put(field, null);
        if (field.equals(sourceIdField) || field.equals(nameField)) {
          warnings.add(error("REQUIRED_VALUE_MISSING", field, "필수 값이 비어 있습니다."));
        }
        continue;
      }
      Object value =
          switch (target.type()) {
            case "number" -> number(field, raw, columnByField.get(field), conversions, warnings);
            case "date" -> date(field, raw, warnings);
            case "boolean" -> bool(field, raw, warnings);
            default -> text(field, raw, warnings);
          };
      values.put(field, value);
    }
    crossCheck(values, warnings);
    Object sourceId = sourceIdField == null ? null : values.get(sourceIdField);
    return new NormalizedRow(
        values, conversions, warnings, sourceId == null ? null : sourceId.toString());
  }

  private Object text(String field, String raw, List<RowWarning> warnings) {
    if (ImportTargetFields.OCCUPANCY_STATUS.equals(field)) {
      return occupancy(raw, warnings);
    }
    int max = MAX_LENGTH.getOrDefault(field, 500);
    if (InputRules.containsHtml(raw)) {
      warnings.add(error("HTML_NOT_ALLOWED", field, "HTML은 입력할 수 없습니다."));
      return raw;
    }
    if (InputRules.length(raw) > max) {
      warnings.add(error("VALUE_TOO_LONG", field, max + "자 이하여야 합니다."));
      return raw;
    }
    if (SENSITIVE_CHECKED.contains(field) && SensitiveDataDetector.detect(raw)) {
      warnings.add(error("SENSITIVE_DATA_DETECTED", field, "민감 번호가 감지됐습니다."));
      return raw;
    }
    if (ImportTargetFields.CUSTOMER_PHONE.equals(field)
        || ImportTargetFields.CUSTOMER_SECONDARY_PHONE.equals(field)) {
      return PhoneNumbers.normalize(raw)
          .orElseGet(
              () -> {
                warnings.add(warning("INVALID_PHONE_FORMAT", field, "전화번호 형식이 올바르지 않습니다."));
                return raw;
              });
    }
    if (ImportTargetFields.CUSTOMER_EMAIL.equals(field) && !EMAIL.matcher(raw).matches()) {
      warnings.add(error("INVALID_EMAIL_FORMAT", field, "이메일 형식이 올바르지 않습니다."));
    }
    return raw;
  }

  private Object occupancy(String raw, List<RowWarning> warnings) {
    String key = raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    return switch (key) {
      case "vacant", "공실", "비어있음" -> "VACANT";
      case "owner_occupied", "자가", "자가거주", "소유자거주", "본인거주" -> "OWNER_OCCUPIED";
      case "tenant_occupied", "임차", "임차인", "세입자", "임차인거주", "세입자거주" -> "TENANT_OCCUPIED";
      case "unknown", "미상", "모름" -> "UNKNOWN";
      default -> {
        warnings.add(
            warning(
                "UNKNOWN_OCCUPANCY_VALUE",
                ImportTargetFields.OCCUPANCY_STATUS,
                "알 수 없는 점유상태 값이라 미상으로 처리합니다."));
        yield "UNKNOWN";
      }
    };
  }

  private Object bool(String field, String raw, List<RowWarning> warnings) {
    String key = raw.strip().toLowerCase(Locale.ROOT);
    if (TRUE_VALUES.contains(key)) {
      return Boolean.TRUE;
    }
    if (FALSE_VALUES.contains(key)) {
      return Boolean.FALSE;
    }
    warnings.add(warning("INVALID_BOOLEAN", field, "참·거짓으로 해석할 수 없어 비워 둡니다."));
    return null;
  }

  private Object date(String field, String raw, List<RowWarning> warnings) {
    String value = raw.strip();
    Matcher parts = DATE_PARTS.matcher(value);
    Matcher compact = DATE_COMPACT.matcher(value);
    try {
      if (parts.matches()) {
        return LocalDate.of(
            Integer.parseInt(parts.group(1)),
            Integer.parseInt(parts.group(2)),
            Integer.parseInt(parts.group(3)));
      }
      if (compact.matches()) {
        return LocalDate.of(
            Integer.parseInt(compact.group(1)),
            Integer.parseInt(compact.group(2)),
            Integer.parseInt(compact.group(3)));
      }
    } catch (DateTimeException e) {
      warnings.add(error("INVALID_DATE", field, "존재하지 않는 날짜입니다."));
      return null;
    }
    if (value.length() >= 10 && value.charAt(4) == '-' && value.charAt(7) == '-') {
      try {
        return LocalDate.parse(value.substring(0, 10));
      } catch (DateTimeException e) {
        warnings.add(error("INVALID_DATE", field, "존재하지 않는 날짜입니다."));
        return null;
      }
    }
    warnings.add(error("INVALID_DATE", field, "날짜 형식을 해석할 수 없습니다."));
    return null;
  }

  private Object number(
      String field,
      String raw,
      String column,
      List<UnitConversion> conversions,
      List<RowWarning> warnings) {
    boolean money = ImportTargetFields.MAINTENANCE_FEE_AMOUNT.equals(field);
    String compact = raw.replace(",", "").replaceAll("\\s+", "");
    Matcher matcher = (money ? MONEY : AREA).matcher(compact);
    if (!matcher.matches()) {
      warnings.add(error("INVALID_NUMBER", field, "숫자로 해석할 수 없습니다."));
      return null;
    }
    BigDecimal number = new BigDecimal(matcher.group(1));
    String unit = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase(Locale.ROOT);
    String hint = column == null ? "" : column;
    if (money) {
      boolean manWon = unit.startsWith("만") || (unit.isEmpty() && hint.contains("만원"));
      if (manWon) {
        BigDecimal converted = number.multiply(MAN_WON).setScale(0, RoundingMode.HALF_UP);
        conversions.add(new UnitConversion(field, raw, converted.longValueExact(), MAN_WON_TO_WON));
        number = converted;
      }
      if (number.signum() < 0) {
        warnings.add(error("OUT_OF_RANGE", field, "0 이상이어야 합니다."));
        return null;
      }
      return number.setScale(0, RoundingMode.HALF_UP).longValue();
    }
    boolean pyeong =
        unit.equals("평") || unit.equals("py") || (unit.isEmpty() && hint.contains("평"));
    BigDecimal area = number;
    if (pyeong) {
      area = number.multiply(SQM_PER_PYEONG).setScale(2, RoundingMode.HALF_UP);
      conversions.add(new UnitConversion(field, raw, area, PYEONG_TO_SQM));
    } else if (number.stripTrailingZeros().scale() > 2) {
      area = number.setScale(2, RoundingMode.HALF_UP);
      warnings.add(warning("AREA_ROUNDED", field, "소수 둘째 자리까지만 저장합니다."));
    }
    if (area.signum() <= 0 || area.compareTo(AREA_LIMIT) > 0) {
      warnings.add(error("OUT_OF_RANGE", field, "허용 범위를 벗어난 면적입니다."));
      return null;
    }
    return area.stripTrailingZeros().scale() < 0 ? area.setScale(0) : area;
  }

  private void crossCheck(Map<String, Object> values, List<RowWarning> warnings) {
    if (Boolean.TRUE.equals(values.get(ImportTargetFields.MOVE_IN_NEGOTIABLE))
        && values.get(ImportTargetFields.MOVE_IN_DATE) != null) {
      warnings.add(
          error(
              "MOVE_IN_CONFLICT",
              ImportTargetFields.MOVE_IN_DATE,
              "입주일 협의이면 입주 가능일을 함께 쓸 수 없습니다."));
    }
  }

  private static String clean(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
  }

  private static RowWarning error(String code, String field, String message) {
    return new RowWarning(code, field, message, Severity.ERROR);
  }

  private static RowWarning warning(String code, String field, String message) {
    return new RowWarning(code, field, message, Severity.WARNING);
  }
}
