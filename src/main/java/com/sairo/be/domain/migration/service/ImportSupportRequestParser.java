package com.sairo.be.domain.migration.service;

import static com.sairo.be.domain.migration.service.ImportRequestParser.invalid;
import static com.sairo.be.domain.migration.service.ImportRequestParser.requireObject;
import static com.sairo.be.domain.migration.service.ImportRequestParser.text;

import com.sairo.be.domain.migration.dto.request.ExportCreateRequest;
import com.sairo.be.domain.migration.dto.request.ExportPolicyUpdateRequest;
import com.sairo.be.domain.migration.dto.request.ImportSupportCreateRequest;
import com.sairo.be.domain.migration.dto.request.ImportSupportPaymentConfirmRequest;
import com.sairo.be.domain.migration.dto.request.ImportSupportReasonRequest;
import com.sairo.be.global.error.FieldErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public final class ImportSupportRequestParser {

  public static final int REASON_MAX = 1_000;
  private static final int SOURCE_SYSTEM_MAX = 100;
  private static final int PAYMENT_KEY_MAX = 200;
  private static final Pattern ORDER_ID = Pattern.compile("^[A-Za-z0-9_-]{6,64}$");

  private ImportSupportRequestParser() {}

  public static ImportSupportCreateRequest create(JsonNode body) {
    requireObject(body, Set.of("importBatchId", "sourceSystem", "estimatedRowCount"));
    long batchId = integer(body, "importBatchId", 1, Long.MAX_VALUE);
    String sourceSystem = text(body, "sourceSystem", "sourceSystem", SOURCE_SYSTEM_MAX);
    long rows = integer(body, "estimatedRowCount", 0, Integer.MAX_VALUE);
    return new ImportSupportCreateRequest(batchId, sourceSystem, (int) rows);
  }

  public static ImportSupportPaymentConfirmRequest paymentConfirm(JsonNode body) {
    requireObject(body, Set.of("paymentKey", "orderId", "amount"));
    String paymentKey = text(body, "paymentKey", "paymentKey", PAYMENT_KEY_MAX);
    String orderId = text(body, "orderId", "orderId", 64);
    if (!ORDER_ID.matcher(orderId).matches()) {
      throw invalid("orderId", FieldErrorCode.INVALID_FORMAT, "주문 ID 형식이 맞지 않습니다.");
    }
    long amount = integer(body, "amount", 0, Long.MAX_VALUE);
    return new ImportSupportPaymentConfirmRequest(paymentKey, orderId, amount);
  }

  public static ImportSupportReasonRequest requiredReason(JsonNode body) {
    requireObject(body, Set.of("reason"));
    return new ImportSupportReasonRequest(text(body, "reason", "reason", REASON_MAX));
  }

  public static ImportSupportReasonRequest optionalReason(JsonNode body) {
    if (body == null || body.isNull() || (body.isObject() && body.isEmpty())) {
      return new ImportSupportReasonRequest(null);
    }
    requireObject(body, Set.of("reason"));
    JsonNode value = body.get("reason");
    if (value == null || value.isNull()) {
      return new ImportSupportReasonRequest(null);
    }
    if (!value.isString()) {
      throw invalid("reason", FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    String reason = value.asString().strip();
    if (reason.length() > REASON_MAX) {
      throw invalid("reason", FieldErrorCode.TOO_LONG, REASON_MAX + "자 이하여야 합니다.");
    }
    return new ImportSupportReasonRequest(reason.isEmpty() ? null : reason);
  }

  public static ExportCreateRequest exportCreate(JsonNode body) {
    requireObject(body, Set.of("scope", "tables"));
    String scope = text(body, "scope", "scope", 10);
    if (!scope.equals("FULL") && !scope.equals("TABLES")) {
      throw invalid("scope", FieldErrorCode.NOT_ALLOWED, "FULL 또는 TABLES여야 합니다.");
    }
    JsonNode tables = body.get("tables");
    boolean absent = tables == null || tables.isNull();
    if (scope.equals("FULL")) {
      if (!absent) {
        throw invalid("tables", FieldErrorCode.NOT_ALLOWED, "FULL에서는 표를 지정할 수 없습니다.");
      }
      return new ExportCreateRequest(scope, List.of());
    }
    if (absent) {
      throw invalid("tables", FieldErrorCode.REQUIRED, "내보낼 표를 하나 이상 보내야 합니다.");
    }
    List<String> codes = tableCodes(tables, "tables");
    if (codes.isEmpty()) {
      throw invalid("tables", FieldErrorCode.REQUIRED, "내보낼 표를 하나 이상 보내야 합니다.");
    }
    return new ExportCreateRequest(scope, codes);
  }

  public static ExportPolicyUpdateRequest exportPolicy(JsonNode body) {
    requireObject(body, Set.of("allowedTables"));
    JsonNode tables = body.get("allowedTables");
    if (tables == null || tables.isNull()) {
      throw invalid("allowedTables", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    return new ExportPolicyUpdateRequest(tableCodes(tables, "allowedTables"));
  }

  private static List<String> tableCodes(JsonNode array, String field) {
    if (!array.isArray()) {
      throw invalid(field, FieldErrorCode.TYPE_MISMATCH, "배열이어야 합니다.");
    }
    Set<String> codes = new LinkedHashSet<>();
    List<String> items = new ArrayList<>();
    for (JsonNode item : array) {
      if (!item.isString()) {
        throw invalid(field + "[]", FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
      }
      String code = item.asString().strip();
      if (!ExportTables.isKnown(code)) {
        throw invalid(field + "[]", FieldErrorCode.NOT_ALLOWED, "내보낼 수 없는 표 코드입니다.");
      }
      if (codes.add(code)) {
        items.add(code);
      }
    }
    return items;
  }

  private static long integer(JsonNode node, String key, long min, long max) {
    JsonNode value = node.get(key);
    if (value == null || value.isNull()) {
      throw invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid(key, FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
    }
    long number = value.asLong();
    if (number < min || number > max) {
      throw invalid(key, FieldErrorCode.OUT_OF_RANGE, "허용 범위를 벗어났습니다.");
    }
    return number;
  }
}
