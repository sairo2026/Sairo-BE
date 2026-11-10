package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.dto.request.ImportMappingConfirmRequest;
import com.sairo.be.domain.migration.dto.request.ImportSheetSelectRequest;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class ImportRequestParser {

  public static final int MAX_MAPPINGS = 100;
  private static final int SOURCE_SYSTEM_MAX = 100;
  private static final int SHEET_NAME_MAX = 100;
  private static final int COLUMN_MAX = 200;
  private static final int FIELD_MAX = 100;

  private ImportRequestParser() {}

  public static ImportSheetSelectRequest sheetSelect(JsonNode body) {
    requireObject(body, Set.of("sheetName"));
    return new ImportSheetSelectRequest(text(body, "sheetName", "sheetName", SHEET_NAME_MAX));
  }

  public static ImportMappingConfirmRequest mappingConfirm(JsonNode body) {
    requireObject(body, Set.of("mappings", "sourceSystem"));
    String sourceSystem = text(body, "sourceSystem", "sourceSystem", SOURCE_SYSTEM_MAX);
    JsonNode array = body.get("mappings");
    if (array == null || array.isNull()) {
      throw invalid("mappings", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (!array.isArray()) {
      throw invalid("mappings", FieldErrorCode.TYPE_MISMATCH, "배열이어야 합니다.");
    }
    if (array.isEmpty()) {
      throw invalid("mappings", FieldErrorCode.REQUIRED, "열 대응을 하나 이상 보내야 합니다.");
    }
    if (array.size() > MAX_MAPPINGS) {
      throw invalid("mappings", FieldErrorCode.OUT_OF_RANGE, MAX_MAPPINGS + "개 이하여야 합니다.");
    }
    List<ImportMappingConfirmRequest.Mapping> mappings = new ArrayList<>();
    for (JsonNode item : array) {
      requireObject(item, Set.of("sourceColumn", "targetField"));
      mappings.add(
          new ImportMappingConfirmRequest.Mapping(
              text(item, "sourceColumn", "mappings[].sourceColumn", COLUMN_MAX),
              text(item, "targetField", "mappings[].targetField", FIELD_MAX)));
    }
    return new ImportMappingConfirmRequest(mappings, sourceSystem);
  }

  private static void requireObject(JsonNode node, Set<String> allowed) {
    if (node == null || !node.isObject()) {
      throw invalid("body", FieldErrorCode.TYPE_MISMATCH, "JSON 객체여야 합니다.");
    }
    for (String name : node.propertyNames()) {
      if (!allowed.contains(name)) {
        throw invalid(name, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
      }
    }
  }

  private static String text(JsonNode node, String key, String field, int max) {
    JsonNode value = node.get(key);
    if (value == null || value.isNull()) {
      throw invalid(field, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (!value.isString()) {
      throw invalid(field, FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
    }
    String text = value.asString().strip();
    if (text.isEmpty()) {
      throw invalid(field, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (text.length() > max) {
      throw invalid(field, FieldErrorCode.TOO_LONG, max + "자 이하여야 합니다.");
    }
    return text;
  }

  public static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
