package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.entity.GroupingPreference;
import com.sairo.be.domain.visit.service.ConditionSpec.DateRule;
import com.sairo.be.domain.visit.service.ConditionSpec.Limit;
import com.sairo.be.domain.visit.service.ConditionSpec.LimitKind;
import com.sairo.be.domain.visit.service.ConditionSpec.Window;
import com.sairo.be.domain.visit.service.ConditionSpec.WindowType;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.support.TimeRules;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public final class ApproverConditionParser {

  public static final int MAX_DATE_RULES = 366;
  public static final int MAX_WINDOWS = 500;
  static final int MAX_VISITS_LIMIT = 20;

  private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
  private static final String END_OF_DAY = "24:00";
  private static final Set<String> BASE_KEYS =
      Set.of(
          "appliesFrom",
          "appliesUntil",
          "maxVisitsPerDay",
          "minGapMinutes",
          "minLeadMinutes",
          "singleVisitMaxMinutes",
          "groupingPreference",
          "windows",
          "dateRules",
          "impactAcknowledged");
  private static final Set<String> WINDOW_KEYS =
      Set.of("windowType", "weekday", "windowDate", "startsTime", "endsTime", "isException");
  private static final Set<String> RULE_KEYS =
      Set.of(
          "ruleDate",
          "maxVisitsPerDay",
          "minGapMinutes",
          "minLeadMinutes",
          "singleVisitMaxMinutes");
  private static final Set<String> LIMIT_KEYS = Set.of("kind", "value");

  public enum Mode {
    LINK_SAVE,
    LINK_PREVIEW,
    PROXY,
    BULK
  }

  public record Parsed(
      ConditionSpec spec,
      List<Window> normalizedRanges,
      int dateRuleReplacedCount,
      boolean impactAcknowledged,
      String acknowledgedNoticeVersion) {}

  private ApproverConditionParser() {}

  public static Parsed parse(JsonNode body, Mode mode) {
    Set<String> allowed = new HashSet<>(BASE_KEYS);
    if (mode == Mode.BULK) {
      allowed.remove("impactAcknowledged");
    } else if (mode != Mode.PROXY) {
      allowed.add("acknowledgedNoticeVersion");
    }
    CustomerInput.requireObject(body, allowed);

    OffsetDateTime from = CustomerInput.requiredDateTime(body, "appliesFrom");
    OffsetDateTime until = CustomerInput.requiredDateTime(body, "appliesUntil");
    if (!until.isAfter(from)) {
      throw CustomerInput.invalid(
          "appliesUntil", FieldErrorCode.INVALID_VALUE, "적용 종료는 적용 시작보다 늦어야 합니다.");
    }
    Integer maxVisits = optionalInt(body, "maxVisitsPerDay", 1, MAX_VISITS_LIMIT);
    Integer minGap = optionalInt(body, "minGapMinutes", 0, Integer.MAX_VALUE);
    Integer minLead = optionalInt(body, "minLeadMinutes", 0, Integer.MAX_VALUE);
    Integer singleMax = optionalInt(body, "singleVisitMaxMinutes", 1, Integer.MAX_VALUE);
    GroupingPreference grouping =
        CustomerInput.isNull(body, "groupingPreference")
            ? null
            : CustomerInput.enumValue(body, "groupingPreference", GroupingPreference.class);

    List<Window> rawWindows = parseWindows(body);
    List<Window> normalizedRanges = new ArrayList<>();
    List<Window> windows = normalize(rawWindows, normalizedRanges);

    LocalDate firstDay = TimeRules.displayDate(from.toInstant());
    LocalDate lastDay = TimeRules.displayDate(until.toInstant());
    int[] replaced = new int[1];
    List<DateRule> dateRules = parseDateRules(body, firstDay, lastDay, replaced);

    boolean acknowledged = false;
    if (mode == Mode.LINK_PREVIEW) {
      if (!CustomerInput.isNull(body, "impactAcknowledged")) {
        acknowledged = CustomerInput.requiredBoolean(body, "impactAcknowledged");
      }
    } else if (mode != Mode.BULK) {
      acknowledged = CustomerInput.requiredBoolean(body, "impactAcknowledged");
    }
    String noticeVersion = null;
    if (mode == Mode.LINK_SAVE) {
      noticeVersion = CustomerInput.requiredText(body, "acknowledgedNoticeVersion", 50, false);
    } else if (mode == Mode.LINK_PREVIEW) {
      noticeVersion = CustomerInput.optionalText(body, "acknowledgedNoticeVersion", 50, false);
    }
    ConditionSpec spec =
        new ConditionSpec(
            from.toInstant(),
            until.toInstant(),
            maxVisits,
            minGap,
            minLead,
            singleMax,
            grouping,
            windows,
            dateRules);
    return new Parsed(spec, normalizedRanges, replaced[0], acknowledged, noticeVersion);
  }

  private static Integer optionalInt(JsonNode body, String key, int min, int max) {
    if (CustomerInput.isNull(body, key)) {
      return null;
    }
    return intValue(body.get(key), key, min, max);
  }

  private static Integer intValue(JsonNode node, String field, int min, int max) {
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw CustomerInput.invalid(field, FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
    }
    int value = node.intValue();
    if (value < min || value > max) {
      throw CustomerInput.invalid(field, FieldErrorCode.OUT_OF_RANGE, "허용 범위를 벗어났습니다.");
    }
    return value;
  }

  private static List<Window> parseWindows(JsonNode body) {
    if (CustomerInput.isNull(body, "windows")) {
      throw CustomerInput.invalid("windows", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    JsonNode node = body.get("windows");
    if (!node.isArray()) {
      throw CustomerInput.invalid("windows", FieldErrorCode.TYPE_MISMATCH, "배열이어야 합니다.");
    }
    if (node.size() > MAX_WINDOWS) {
      throw CustomerInput.invalid(
          "windows", FieldErrorCode.OUT_OF_RANGE, MAX_WINDOWS + "개까지 등록할 수 있습니다.");
    }
    List<Window> windows = new ArrayList<>();
    int index = 0;
    for (JsonNode element : node) {
      String prefix = "windows[" + index + "].";
      CustomerInput.requireObject(element, WINDOW_KEYS);
      WindowType type = CustomerInput.requiredEnum(element, "windowType", WindowType.class);
      Integer weekday = null;
      LocalDate date = null;
      if (type == WindowType.WEEKDAY) {
        if (!CustomerInput.isNull(element, "windowDate")) {
          throw CustomerInput.invalid(
              prefix + "windowDate", FieldErrorCode.NOT_ALLOWED, "요일 반복에는 날짜를 둘 수 없습니다.");
        }
        if (CustomerInput.isNull(element, "weekday")) {
          throw CustomerInput.invalid(prefix + "weekday", FieldErrorCode.REQUIRED, "필수 입력입니다.");
        }
        weekday = intValue(element.get("weekday"), prefix + "weekday", 1, 7);
      } else {
        if (!CustomerInput.isNull(element, "weekday")) {
          throw CustomerInput.invalid(
              prefix + "weekday", FieldErrorCode.NOT_ALLOWED, "특정 날짜에는 요일을 둘 수 없습니다.");
        }
        date = CustomerInput.requiredDate(element, "windowDate");
      }
      int start = minute(element, "startsTime", prefix, false);
      int end = minute(element, "endsTime", prefix, true);
      if (end <= start) {
        throw CustomerInput.invalid(
            prefix + "endsTime", FieldErrorCode.INVALID_VALUE, "종료 시각은 시작 시각보다 늦어야 합니다.");
      }
      boolean exception = CustomerInput.requiredBoolean(element, "isException");
      windows.add(new Window(type, weekday, date, start, end, exception));
      index++;
    }
    return windows;
  }

  private static int minute(JsonNode element, String key, String prefix, boolean allowEndOfDay) {
    if (CustomerInput.isNull(element, key)) {
      throw CustomerInput.invalid(prefix + key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    JsonNode node = element.get(key);
    if (!node.isString()) {
      throw CustomerInput.invalid(prefix + key, FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    String text = node.asString();
    if (allowEndOfDay && END_OF_DAY.equals(text)) {
      return 24 * 60;
    }
    Matcher matcher = TIME.matcher(text);
    if (!matcher.matches()) {
      throw CustomerInput.invalid(prefix + key, FieldErrorCode.INVALID_FORMAT, "HH:mm 형식이어야 합니다.");
    }
    return Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
  }

  static List<Window> normalize(List<Window> raw, List<Window> mergedOut) {
    Map<String, List<Window>> groups = new LinkedHashMap<>();
    for (Window window : raw) {
      String key =
          window.type()
              + ":"
              + (window.type() == WindowType.WEEKDAY ? window.weekday() : window.date())
              + ":"
              + window.exception();
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(window);
    }
    List<Window> result = new ArrayList<>();
    for (List<Window> group : groups.values()) {
      group.sort(Comparator.comparingInt(Window::startMinute).thenComparingInt(Window::endMinute));
      Window current = group.get(0);
      int merged = 1;
      for (int i = 1; i < group.size(); i++) {
        Window next = group.get(i);
        if (next.startMinute() <= current.endMinute()) {
          current =
              new Window(
                  current.type(),
                  current.weekday(),
                  current.date(),
                  current.startMinute(),
                  Math.max(current.endMinute(), next.endMinute()),
                  current.exception());
          merged++;
        } else {
          result.add(current);
          if (merged > 1) {
            mergedOut.add(current);
          }
          current = next;
          merged = 1;
        }
      }
      result.add(current);
      if (merged > 1) {
        mergedOut.add(current);
      }
    }
    return result;
  }

  private static List<DateRule> parseDateRules(
      JsonNode body, LocalDate firstDay, LocalDate lastDay, int[] replaced) {
    if (CustomerInput.isNull(body, "dateRules")) {
      return List.of();
    }
    JsonNode node = body.get("dateRules");
    if (!node.isArray()) {
      throw CustomerInput.invalid("dateRules", FieldErrorCode.TYPE_MISMATCH, "배열이어야 합니다.");
    }
    if (node.size() > MAX_DATE_RULES) {
      throw CustomerInput.invalid(
          "dateRules", FieldErrorCode.OUT_OF_RANGE, MAX_DATE_RULES + "개까지 등록할 수 있습니다.");
    }
    Map<LocalDate, Limit[]> byDate = new LinkedHashMap<>();
    int index = 0;
    for (JsonNode element : node) {
      String prefix = "dateRules[" + index + "].";
      CustomerInput.requireObject(element, RULE_KEYS);
      LocalDate date = CustomerInput.requiredDate(element, "ruleDate");
      if (date.isBefore(firstDay) || date.isAfter(lastDay)) {
        throw CustomerInput.invalid(
            prefix + "ruleDate", FieldErrorCode.OUT_OF_RANGE, "적용 기간 안의 날짜여야 합니다.");
      }
      Limit[] items = {
        limit(element, "maxVisitsPerDay", prefix, 1, MAX_VISITS_LIMIT),
        limit(element, "minGapMinutes", prefix, 0, Integer.MAX_VALUE),
        limit(element, "minLeadMinutes", prefix, 0, Integer.MAX_VALUE),
        limit(element, "singleVisitMaxMinutes", prefix, 1, Integer.MAX_VALUE)
      };
      boolean any = false;
      for (Limit item : items) {
        any |= item != null;
      }
      if (!any) {
        throw CustomerInput.invalid(
            prefix + "ruleDate", FieldErrorCode.INVALID_VALUE, "네 항목이 모두 비어 있으면 안 됩니다.");
      }
      Limit[] merged = byDate.computeIfAbsent(date, d -> new Limit[4]);
      for (int i = 0; i < items.length; i++) {
        if (items[i] != null) {
          if (merged[i] != null) {
            replaced[0]++;
          }
          merged[i] = items[i];
        }
      }
      index++;
    }
    List<DateRule> rules = new ArrayList<>();
    byDate.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              Limit[] l = entry.getValue();
              rules.add(new DateRule(entry.getKey(), l[0], l[1], l[2], l[3]));
            });
    return rules;
  }

  private static Limit limit(JsonNode element, String key, String prefix, int min, int max) {
    if (CustomerInput.isNull(element, key)) {
      return null;
    }
    JsonNode node = element.get(key);
    String field = prefix + key;
    CustomerInput.requireObject(node, LIMIT_KEYS);
    LimitKind kind = CustomerInput.requiredEnum(node, "kind", LimitKind.class);
    if (kind == LimitKind.UNLIMITED) {
      if (!CustomerInput.isNull(node, "value")) {
        throw CustomerInput.invalid(
            field + ".value", FieldErrorCode.NOT_ALLOWED, "제한 없음에는 값을 둘 수 없습니다.");
      }
      return new Limit(kind, null);
    }
    if (CustomerInput.isNull(node, "value")) {
      throw CustomerInput.invalid(field + ".value", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    return new Limit(kind, intValue(node.get("value"), field + ".value", min, max));
  }
}
