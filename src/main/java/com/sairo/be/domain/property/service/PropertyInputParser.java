package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.entity.OccupancyStatus;
import com.sairo.be.domain.property.entity.PropertyAttributes;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.support.InputRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class PropertyInputParser {

  private static final BigDecimal AREA_LIMIT = new BigDecimal("9999999999.99");

  private static final Set<String> COMMON_KEYS =
      Set.of(
          "name",
          "address",
          "addressDetail",
          "managingMembershipId",
          "exclusiveAreaSqm",
          "supplyAreaSqm",
          "landAreaSqm",
          "grossFloorAreaSqm",
          "moveInDate",
          "moveInNegotiable",
          "maintenanceFeeAmount",
          "maintenanceFeeItems",
          "occupancyStatus",
          "description",
          "memo",
          "referenceLeaseEndDate",
          "referenceLeaseEndSource",
          "duplicateOverrideReason");

  public record Parsed(Map<String, Object> values, Long categoryId, boolean activate) {

    public boolean has(String key) {
      return values.containsKey(key);
    }
  }

  private PropertyInputParser() {}

  public static Parsed parseCreate(JsonNode body) {
    requireObject(body);
    rejectUnknown(body, Set.of("categoryId"));
    JsonNode category = body.get("categoryId");
    if (category == null || category.isNull()) {
      throw invalid("categoryId", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    Long categoryId = integral("categoryId", category);
    if (!body.has("name") || body.get("name").isNull()) {
      throw invalid("name", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    return new Parsed(readCommon(body, true), categoryId, false);
  }

  public static Parsed parsePatch(JsonNode body) {
    requireObject(body);
    rejectUnknown(body, Set.of("registrationStage"));
    boolean activate = false;
    if (body.has("registrationStage")) {
      JsonNode stage = body.get("registrationStage");
      if (!stage.isString() || !"ACTIVE".equals(stage.asString())) {
        throw invalid("registrationStage", FieldErrorCode.NOT_ALLOWED, "ACTIVE만 보낼 수 있습니다.");
      }
      activate = true;
    }
    Map<String, Object> values = readCommon(body, false);
    if (values.isEmpty() && !activate) {
      throw invalid("body", FieldErrorCode.REQUIRED, "변경할 필드를 하나 이상 보내야 합니다.");
    }
    return new Parsed(values, null, activate);
  }

  public static PropertyAttributes initial(Map<String, Object> values) {
    return merge(
        new PropertyAttributes(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            OccupancyStatus.UNKNOWN,
            null,
            null,
            null,
            null,
            null),
        values);
  }

  public static PropertyAttributes merge(PropertyAttributes base, Map<String, Object> values) {
    return new PropertyAttributes(
        pick(values, "name", base.name()),
        pick(values, "address", base.address()),
        pick(values, "addressDetail", base.addressDetail()),
        pick(values, "managingMembershipId", base.managingMembershipId()),
        pick(values, "exclusiveAreaSqm", base.exclusiveAreaSqm()),
        pick(values, "supplyAreaSqm", base.supplyAreaSqm()),
        pick(values, "landAreaSqm", base.landAreaSqm()),
        pick(values, "grossFloorAreaSqm", base.grossFloorAreaSqm()),
        pick(values, "moveInDate", base.moveInDate()),
        pick(values, "moveInNegotiable", base.moveInNegotiable()),
        pick(values, "maintenanceFeeAmount", base.maintenanceFeeAmount()),
        pick(values, "maintenanceFeeItems", base.maintenanceFeeItems()),
        pick(values, "occupancyStatus", base.occupancyStatus()),
        pick(values, "description", base.description()),
        pick(values, "memo", base.memo()),
        pick(values, "referenceLeaseEndDate", base.referenceLeaseEndDate()),
        pick(values, "referenceLeaseEndSource", base.referenceLeaseEndSource()),
        pick(values, "duplicateOverrideReason", base.duplicateOverrideReason()));
  }

  public static void validateCombination(PropertyAttributes attributes) {
    if (attributes.moveInNegotiable() && attributes.moveInDate() != null) {
      throw invalid("moveInDate", FieldErrorCode.NOT_ALLOWED, "입주일 협의이면 입주 가능일을 보낼 수 없습니다.");
    }
    if (attributes.referenceLeaseEndDate() != null
        && attributes.referenceLeaseEndSource() == null) {
      throw invalid("referenceLeaseEndSource", FieldErrorCode.REQUIRED, "참고 만기일에는 출처가 필요합니다.");
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T pick(Map<String, Object> values, String key, T fallback) {
    return values.containsKey(key) ? (T) values.get(key) : fallback;
  }

  private static Map<String, Object> readCommon(JsonNode body, boolean create) {
    Map<String, Object> values = new HashMap<>();
    for (String key : COMMON_KEYS) {
      if (!body.has(key)) {
        continue;
      }
      values.put(key, readValue(key, body.get(key), create));
    }
    return values;
  }

  private static Object readValue(String key, JsonNode node, boolean create) {
    boolean isNull = node.isNull();
    return switch (key) {
      case "name" -> text(key, node, 1, InputRules.NAME_MAX);
      case "address" -> isNull ? null : text(key, node, 1, 300);
      case "addressDetail" -> isNull ? null : text(key, node, 1, 100);
      case "maintenanceFeeItems" -> isNull ? null : text(key, node, 1, InputRules.SHORT_MEMO_MAX);
      case "description", "memo" -> isNull ? null : text(key, node, 1, InputRules.BODY_MEMO_MAX);
      case "referenceLeaseEndSource" -> isNull ? null : text(key, node, 1, 200);
      case "duplicateOverrideReason" -> isNull ? null : text(key, node, 1, 500);
      case "managingMembershipId" -> isNull ? null : integral(key, node);
      case "exclusiveAreaSqm", "supplyAreaSqm", "landAreaSqm", "grossFloorAreaSqm" ->
          isNull ? null : area(key, node);
      case "moveInDate", "referenceLeaseEndDate" -> isNull ? null : date(key, node);
      case "moveInNegotiable" -> bool(key, node);
      case "maintenanceFeeAmount" -> isNull ? null : nonNegative(key, node);
      case "occupancyStatus" -> occupancy(key, node, create);
      default -> throw invalid(key, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
    };
  }

  private static String text(String key, JsonNode node, int min, int max) {
    if (node.isNull()) {
      throw invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (!node.isString()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    return InputRules.requireText(key, node.asString(), min, max);
  }

  private static Long integral(String key, JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
    }
    return node.longValue();
  }

  private static Long nonNegative(String key, JsonNode node) {
    long value = integral(key, node);
    if (value < 0) {
      throw invalid(key, FieldErrorCode.OUT_OF_RANGE, "0 이상이어야 합니다.");
    }
    return value;
  }

  private static BigDecimal area(String key, JsonNode node) {
    if (!node.isNumber()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "숫자여야 합니다.");
    }
    BigDecimal value = InputRules.requireArea(key, node.decimalValue());
    if (value.compareTo(AREA_LIMIT) > 0) {
      throw invalid(key, FieldErrorCode.OUT_OF_RANGE, "허용 범위를 넘었습니다.");
    }
    return value;
  }

  private static LocalDate date(String key, JsonNode node) {
    if (!node.isString()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "날짜 문자열이어야 합니다.");
    }
    try {
      return LocalDate.parse(node.asString());
    } catch (DateTimeParseException e) {
      throw invalid(key, FieldErrorCode.INVALID_FORMAT, "yyyy-MM-dd 형식이어야 합니다.");
    }
  }

  private static Boolean bool(String key, JsonNode node) {
    if (!node.isBoolean()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "true 또는 false여야 합니다.");
    }
    return node.booleanValue();
  }

  private static OccupancyStatus occupancy(String key, JsonNode node, boolean create) {
    if (node.isNull()) {
      if (create) {
        return OccupancyStatus.UNKNOWN;
      }
      throw invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (!node.isString()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    try {
      return OccupancyStatus.valueOf(node.asString());
    } catch (IllegalArgumentException e) {
      throw invalid(key, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
  }

  private static void requireObject(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw invalid("body", FieldErrorCode.TYPE_MISMATCH, "JSON 객체여야 합니다.");
    }
  }

  private static void rejectUnknown(JsonNode body, Set<String> extra) {
    for (String name : body.propertyNames()) {
      if (!COMMON_KEYS.contains(name) && !extra.contains(name)) {
        throw invalid(name, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
      }
    }
  }

  private static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
