package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.dto.request.ImportMappingConfirmRequest;
import com.sairo.be.domain.migration.dto.response.ImportMappingConfirmResponse;
import com.sairo.be.domain.migration.dto.response.ImportMappingSuggestionResponse;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportBatchStatus;
import com.sairo.be.domain.migration.entity.ImportMappingItem;
import com.sairo.be.domain.migration.entity.ImportMappingVersion;
import com.sairo.be.domain.migration.mapper.ImportBatchMapper;
import com.sairo.be.domain.migration.repository.ImportBatchRepository;
import com.sairo.be.domain.migration.repository.ImportMappingItemRepository;
import com.sairo.be.domain.migration.repository.ImportMappingVersionRepository;
import com.sairo.be.domain.migration.service.HeaderMappingSuggester.Suggestion;
import com.sairo.be.domain.migration.service.parser.ParsedRow;
import com.sairo.be.domain.migration.service.parser.ParsedSheet;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ImportMappingService {

  private static final int SAMPLE_LIMIT = 3;

  private final ImportGuard guard;
  private final ImportBatchRepository batchRepository;
  private final ImportMappingVersionRepository versionRepository;
  private final ImportMappingItemRepository itemRepository;
  private final ImportFileReader fileReader;
  private final HeaderMappingSuggester suggester;

  public ImportMappingService(
      ImportGuard guard,
      ImportBatchRepository batchRepository,
      ImportMappingVersionRepository versionRepository,
      ImportMappingItemRepository itemRepository,
      ImportFileReader fileReader,
      HeaderMappingSuggester suggester) {
    this.guard = guard;
    this.batchRepository = batchRepository;
    this.versionRepository = versionRepository;
    this.itemRepository = itemRepository;
    this.fileReader = fileReader;
    this.suggester = suggester;
  }

  @Transactional(readOnly = true)
  public ImportMappingSuggestionResponse suggestion(StaffPrincipal principal, Long batchId) {
    ImportBatch batch = guard.find(principal.officeId(), batchId);
    ParsedSheet sheet = fileReader.readSelectedSheet(batch);
    List<Suggestion> suggestions = suggester.suggest(batch.getTargetType(), sheet.headers());
    Map<String, String> mapped = new HashMap<>();
    suggestions.forEach(item -> mapped.put(item.sourceColumn(), item.targetField()));
    if (batch.getStatus() == ImportBatchStatus.MAPPING
        || batch.getStatus() == ImportBatchStatus.PREVIEW) {
      versionRepository
          .findFirstByImportBatchIdOrderByVersionNoDesc(batchId)
          .ifPresent(
              version -> {
                mapped.clear();
                itemRepository
                    .findByMappingVersionIdOrderById(version.getId())
                    .forEach(item -> mapped.put(item.getSourceColumn(), item.getTargetField()));
              });
    }
    List<ImportMappingSuggestionResponse.SourceColumn> columns = new ArrayList<>();
    for (int index = 0; index < sheet.headers().size(); index++) {
      String header = sheet.headers().get(index);
      columns.add(
          new ImportMappingSuggestionResponse.SourceColumn(
              header, samples(sheet, index), mapped.get(header)));
    }
    return new ImportMappingSuggestionResponse(
        columns,
        suggestions.stream()
            .map(
                item ->
                    new ImportMappingSuggestionResponse.SuggestedMapping(
                        item.sourceColumn(), item.targetField(), item.confidence()))
            .toList(),
        ImportTargetFields.forType(batch.getTargetType()).stream()
            .map(
                field ->
                    new ImportMappingSuggestionResponse.AvailableTargetField(
                        field.field(), field.label(), field.type(), field.required()))
            .toList());
  }

  @Transactional
  public ImportMappingConfirmResponse confirm(
      StaffPrincipal principal, Long batchId, long expectedVersion, JsonNode body) {
    ImportMappingConfirmRequest request = ImportRequestParser.mappingConfirm(body);
    ImportBatch batch = guard.lock(principal.officeId(), batchId);
    guard.requireNotManagedBySupport(batchId);
    guard.requireEditable(batch);
    guard.requireVersion(batch, expectedVersion);
    ParsedSheet sheet = fileReader.readSelectedSheet(batch);
    validate(batch, request, sheet);
    Map<String, Double> suggested =
        suggester.suggest(batch.getTargetType(), sheet.headers()).stream()
            .collect(
                Collectors.toMap(
                    item -> item.sourceColumn() + "\n" + item.targetField(),
                    Suggestion::confidence,
                    (left, right) -> left));
    int versionNo = versionRepository.findMaxVersionNo(batchId) + 1;
    ImportMappingVersion version =
        versionRepository.saveAndFlush(
            ImportMappingVersion.byMembership(
                batch, versionNo, request.sourceSystem(), principal.membershipId()));
    List<ImportMappingItem> items = new ArrayList<>();
    for (ImportMappingConfirmRequest.Mapping mapping : request.mappings()) {
      Double confidence = suggested.get(mapping.sourceColumn() + "\n" + mapping.targetField());
      items.add(
          ImportMappingItem.of(
              version.getId(),
              mapping.sourceColumn(),
              mapping.targetField(),
              confidence == null
                  ? null
                  : BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP)));
    }
    itemRepository.saveAll(items);
    batch.confirmMapping(request.sourceSystem(), sheet.rowCount());
    return ImportBatchMapper.toMappingConfirm(batchRepository.saveAndFlush(batch));
  }

  private void validate(ImportBatch batch, ImportMappingConfirmRequest request, ParsedSheet sheet) {
    List<FieldErrorItem> errors = new ArrayList<>();
    Set<String> headers = new HashSet<>(sheet.headers());
    Set<String> allowed =
        ImportTargetFields.forType(batch.getTargetType()).stream()
            .map(ImportTargetField::field)
            .collect(Collectors.toSet());
    Set<String> columns = new HashSet<>();
    Set<String> fields = new HashSet<>();
    for (ImportMappingConfirmRequest.Mapping mapping : request.mappings()) {
      if (!headers.contains(mapping.sourceColumn())) {
        errors.add(
            new FieldErrorItem(
                "mappings[].sourceColumn", FieldErrorCode.NOT_ALLOWED, "선택한 시트에 없는 열입니다."));
      } else if (!columns.add(mapping.sourceColumn())) {
        errors.add(
            new FieldErrorItem(
                "mappings[].sourceColumn", FieldErrorCode.INVALID_VALUE, "같은 열을 두 번 연결할 수 없습니다."));
      }
      if (!allowed.contains(mapping.targetField())) {
        errors.add(
            new FieldErrorItem(
                "mappings[].targetField", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 대상 항목입니다."));
      } else if (!fields.add(mapping.targetField())) {
        errors.add(
            new FieldErrorItem(
                "mappings[].targetField",
                FieldErrorCode.INVALID_VALUE,
                "같은 대상 항목을 두 열에 연결할 수 없습니다."));
      }
    }
    for (ImportTargetField field : ImportTargetFields.forType(batch.getTargetType())) {
      if (field.required() && !fields.contains(field.field())) {
        errors.add(
            new FieldErrorItem(
                "mappings", FieldErrorCode.REQUIRED, field.label() + " 항목을 열에 연결해야 합니다."));
      }
    }
    if (!errors.isEmpty()) {
      throw BusinessException.withFieldErrors(ErrorCode.INVALID_REQUEST, errors);
    }
  }

  private static List<String> samples(ParsedSheet sheet, int index) {
    List<String> samples = new ArrayList<>();
    for (ParsedRow row : sheet.rows()) {
      String value = row.cell(index);
      if (value != null) {
        samples.add(value);
        if (samples.size() == SAMPLE_LIMIT) {
          break;
        }
      }
    }
    return samples;
  }
}
