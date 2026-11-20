package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.dto.response.ImportRowResultsResponse;
import com.sairo.be.domain.migration.dto.response.ImportRowResultsResponse.FieldValue;
import com.sairo.be.domain.migration.dto.response.ImportRowResultsResponse.Issue;
import com.sairo.be.domain.migration.dto.response.ImportRowResultsResponse.Row;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportBatchStatus;
import com.sairo.be.domain.migration.entity.ImportRowOutcome;
import com.sairo.be.domain.migration.entity.ImportRowResult;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import com.sairo.be.domain.migration.repository.ImportRecordLinkRepository;
import com.sairo.be.domain.migration.repository.ImportRowCorrectionJdbcRepository;
import com.sairo.be.domain.migration.repository.ImportRowResultRepository;
import com.sairo.be.domain.migration.service.parser.ParsedRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ImportRowResultService {

  public static final int DEFAULT_SIZE = 50;
  public static final int MAX_SIZE = 100;
  private static final int VALUE_MAX = 10_000;
  private static final Set<ImportBatchStatus> READABLE =
      Set.of(
          ImportBatchStatus.EXECUTING,
          ImportBatchStatus.COMPLETED,
          ImportBatchStatus.FAILED,
          ImportBatchStatus.CANCELLED);
  private static final String BOM = "﻿";

  private final ImportGuard guard;
  private final ImportRowResultRepository rowResultRepository;
  private final ImportRecordLinkRepository linkRepository;
  private final ImportRowCorrectionJdbcRepository correctionRepository;
  private final ImportFileReader fileReader;
  private final ImportRowNormalizer normalizer;

  public ImportRowResultService(
      ImportGuard guard,
      ImportRowResultRepository rowResultRepository,
      ImportRecordLinkRepository linkRepository,
      ImportRowCorrectionJdbcRepository correctionRepository,
      ImportFileReader fileReader,
      ImportRowNormalizer normalizer) {
    this.guard = guard;
    this.rowResultRepository = rowResultRepository;
    this.linkRepository = linkRepository;
    this.correctionRepository = correctionRepository;
    this.fileReader = fileReader;
    this.normalizer = normalizer;
  }

  @Transactional(readOnly = true)
  public ImportRowResultsResponse list(
      StaffPrincipal principal, Long batchId, String outcomeInput, Integer page, Integer size) {
    ImportBatch batch = guard.find(principal.officeId(), batchId);
    requireReadable(batch);
    ImportRowOutcome outcome = parseOutcome(outcomeInput);
    int pageIndex = page == null ? 0 : page;
    int pageSize = size == null ? DEFAULT_SIZE : size;
    if (pageIndex < 0) {
      throw ImportRequestParser.invalid("page", FieldErrorCode.OUT_OF_RANGE, "0 이상이어야 합니다.");
    }
    if (pageSize < 1 || pageSize > MAX_SIZE) {
      throw ImportRequestParser.invalid(
          "size", FieldErrorCode.OUT_OF_RANGE, "1 이상 " + MAX_SIZE + " 이하여야 합니다.");
    }
    PageRequest request = PageRequest.of(pageIndex, pageSize, Sort.by("sourceRowNumber"));
    Page<ImportRowResult> found =
        outcome == null
            ? rowResultRepository.findByBatchId(batchId, request)
            : rowResultRepository.findByBatchIdAndResult(batchId, outcome, request);
    ImportLoadedData data = fileReader.loadWithLatestMapping(batch);
    Map<Integer, ParsedRow> byNumber = rowsByNumber(data);
    Map<Integer, Map<String, String>> corrections = correctionRepository.findByBatch(batchId);
    List<Row> rows = new ArrayList<>();
    for (ImportRowResult result : found.getContent()) {
      rows.add(
          toRow(
              batch,
              data,
              result,
              byNumber.get(result.getSourceRowNumber()),
              corrections.getOrDefault(result.getSourceRowNumber(), Map.of())));
    }
    return new ImportRowResultsResponse(
        rows,
        new ImportRowResultsResponse.Summary(
            rowResultRepository.countByBatchIdAndResult(batchId, ImportRowOutcome.SUCCEEDED),
            rowResultRepository.countByBatchIdAndResult(batchId, ImportRowOutcome.FAILED),
            rowResultRepository.countByBatchIdAndResult(batchId, ImportRowOutcome.SKIPPED)),
        pageIndex,
        pageSize,
        found.getTotalElements());
  }

  @Transactional
  public Row correct(StaffPrincipal principal, Long batchId, int rowNumber, JsonNode body) {
    Map<String, String> input = parseCorrection(body);
    ImportBatch batch = guard.lock(principal.officeId(), batchId);
    guard.requireNotManagedBySupport(batchId);
    if (batch.getStatus() != ImportBatchStatus.COMPLETED
        && batch.getStatus() != ImportBatchStatus.FAILED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    ImportRowResult result =
        rowResultRepository
            .findByBatchIdAndSourceRowNumber(batchId, rowNumber)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (result.getResult() != ImportRowOutcome.FAILED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    ImportLoadedData data = fileReader.loadWithLatestMapping(batch);
    Set<String> mapped = data.columnByField().keySet();
    for (String field : input.keySet()) {
      if (!mapped.contains(field)) {
        throw ImportRequestParser.invalid(
            "values." + field, FieldErrorCode.NOT_ALLOWED, "이 이전에서 연결하지 않은 항목입니다.");
      }
    }
    Map<String, String> merged = new LinkedHashMap<>(correctionRepository.find(batchId, rowNumber));
    merged.putAll(input);
    correctionRepository.save(batchId, rowNumber, merged);
    return toRow(batch, data, result, rowsByNumber(data).get(rowNumber), merged);
  }

  @Transactional(readOnly = true)
  public byte[] failedRowsCsv(StaffPrincipal principal, Long batchId) {
    ImportBatch batch = guard.find(principal.officeId(), batchId);
    requireReadable(batch);
    ImportLoadedData data = fileReader.loadWithLatestMapping(batch);
    Map<Integer, ParsedRow> byNumber = rowsByNumber(data);
    Map<Integer, Map<String, String>> corrections = correctionRepository.findByBatch(batchId);
    StringBuilder out = new StringBuilder(BOM);
    List<String> header = new ArrayList<>();
    header.add("원본 행 번호");
    header.addAll(data.sheet().headers());
    header.add("실패 원인");
    header.add("고치는 방법");
    appendLine(out, header);
    for (ImportRowResult result : rowResultRepository.findFailed(batchId)) {
      ParsedRow row = byNumber.get(result.getSourceRowNumber());
      Row view =
          toRow(
              batch,
              data,
              result,
              row,
              corrections.getOrDefault(result.getSourceRowNumber(), Map.of()));
      List<String> cells = new ArrayList<>();
      cells.add(String.valueOf(result.getSourceRowNumber()));
      for (int index = 0; index < data.sheet().headers().size(); index++) {
        cells.add(row == null ? "" : row.cell(index));
      }
      cells.add(view.issues().stream().map(Issue::message).collect(Collectors.joining(" / ")));
      cells.add(view.issues().stream().map(Issue::fix).collect(Collectors.joining(" / ")));
      appendLine(out, cells);
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private Row toRow(
      ImportBatch batch,
      ImportLoadedData data,
      ImportRowResult result,
      ParsedRow parsed,
      Map<String, String> corrections) {
    ImportTargetType type = batch.getTargetType();
    String label = null;
    String sourceId = null;
    String displayId = null;
    List<Issue> issues = List.of();
    List<FieldValue> fields = List.of();
    boolean ready = false;
    Long targetId = result.getMatchedEntityId();
    NormalizedRow normalized = null;
    Map<String, String> raw = null;
    if (parsed != null) {
      raw = new LinkedHashMap<>(data.rawByField(parsed));
      raw.putAll(corrections);
      normalized =
          normalizer.normalize(
              type,
              raw,
              data.columnByField(),
              ImportTargetFields.autoSourceId(batch.getId(), result.getSourceRowNumber()));
      sourceId = normalized.sourceId();
      displayId = normalized.displaySourceId();
      Object name = normalized.values().get(ImportTargetFields.nameField(type));
      label = name == null ? null : name.toString();
    }
    ImportRowOutcome outcome = result.getResult();
    if (outcome == ImportRowOutcome.SUCCEEDED && sourceId != null) {
      targetId =
          linkRepository
              .findByOfficeIdAndSourceSystemAndSourceTypeAndSourceId(
                  batch.getOfficeId(), data.mapping().getSourceSystem(), type, sourceId)
              .map(link -> link.getTargetEntityId())
              .orElse(null);
    }
    if (outcome == ImportRowOutcome.FAILED) {
      if (normalized != null && normalized.hasError()) {
        issues = ImportFailureDescriber.describe(type, normalized.errorSummary());
      } else if (!corrections.isEmpty()) {
        ready = true;
      } else {
        issues = ImportFailureDescriber.describe(type, result.getFailureReason());
      }
      fields = fieldValues(type, data, raw, corrections);
    }
    return new Row(
        result.getSourceRowNumber(),
        outcome.name(),
        label,
        displayId,
        issues,
        result.getSkipReason() == null ? null : result.getSkipReason().name(),
        targetId,
        fields,
        ready);
  }

  private static List<FieldValue> fieldValues(
      ImportTargetType type,
      ImportLoadedData data,
      Map<String, String> raw,
      Map<String, String> corrections) {
    List<FieldValue> fields = new ArrayList<>();
    if (raw == null) {
      return fields;
    }
    for (ImportTargetField target : ImportTargetFields.forType(type)) {
      if (data.columnByField().containsKey(target.field())) {
        fields.add(
            new FieldValue(
                target.field(),
                target.label(),
                raw.get(target.field()),
                corrections.containsKey(target.field())));
      }
    }
    return fields;
  }

  private static Map<Integer, ParsedRow> rowsByNumber(ImportLoadedData data) {
    Map<Integer, ParsedRow> byNumber = new HashMap<>();
    for (ParsedRow row : data.sheet().rows()) {
      byNumber.put(row.rowNumber(), row);
    }
    return byNumber;
  }

  private static void requireReadable(ImportBatch batch) {
    if (!READABLE.contains(batch.getStatus())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  private static ImportRowOutcome parseOutcome(String input) {
    if (input == null || input.isBlank()) {
      return null;
    }
    try {
      return ImportRowOutcome.valueOf(input.strip());
    } catch (IllegalArgumentException e) {
      throw ImportRequestParser.invalid("outcome", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
  }

  private static Map<String, String> parseCorrection(JsonNode body) {
    ImportRequestParser.requireObject(body, Set.of("values"));
    JsonNode values = body.get("values");
    if (values == null || !values.isObject()) {
      throw ImportRequestParser.invalid("values", FieldErrorCode.TYPE_MISMATCH, "JSON 객체여야 합니다.");
    }
    if (values.isEmpty()) {
      throw ImportRequestParser.invalid("values", FieldErrorCode.REQUIRED, "고친 값을 하나 이상 보내야 합니다.");
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (String name : values.propertyNames()) {
      JsonNode value = values.get(name);
      if (value == null || !value.isString()) {
        throw ImportRequestParser.invalid(
            "values." + name, FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
      }
      if (value.asString().length() > VALUE_MAX) {
        throw ImportRequestParser.invalid(
            "values." + name, FieldErrorCode.TOO_LONG, VALUE_MAX + "자 이하여야 합니다.");
      }
      result.put(name, value.asString());
    }
    return result;
  }

  private static void appendLine(StringBuilder out, List<String> cells) {
    out.append(cells.stream().map(ImportRowResultService::csvCell).collect(Collectors.joining(",")))
        .append("\r\n");
  }

  /** 부호가 붙은 값은 문자열 전체가 숫자일 때만 그대로 내보낸다. 숫자로 시작해도 뒤에 수식이 붙으면 접두 따옴표를 붙인다. */
  private static final java.util.regex.Pattern PLAIN_NUMBER =
      java.util.regex.Pattern.compile("[+-][0-9]+(\\.[0-9]+)?");

  static String csvCell(String value) {
    String text = value == null ? "" : value;
    if (isFormulaLike(text)) {
      text = "'" + text;
    }
    if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
      return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    return text;
  }

  private static boolean isFormulaLike(String text) {
    if (text.isEmpty()) {
      return false;
    }
    char first = text.charAt(0);
    if (first == '=' || first == '@' || first == '\t' || first == '\r') {
      return true;
    }
    if (first == '+' || first == '-') {
      return !PLAIN_NUMBER.matcher(text).matches();
    }
    return false;
  }
}
