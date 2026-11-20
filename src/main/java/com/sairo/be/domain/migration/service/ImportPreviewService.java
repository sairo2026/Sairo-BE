package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.customer.service.CustomerImportService;
import com.sairo.be.domain.migration.dto.response.ImportPreviewResponse;
import com.sairo.be.domain.migration.dto.response.ImportPreviewResponse.DuplicateWarning;
import com.sairo.be.domain.migration.dto.response.ImportPreviewResponse.PreviewRow;
import com.sairo.be.domain.migration.dto.response.ImportPreviewResponse.RelationMatch;
import com.sairo.be.domain.migration.dto.response.ImportPreviewResponse.Summary;
import com.sairo.be.domain.migration.dto.response.ImportPreviewResponse.UnitConversion;
import com.sairo.be.domain.migration.dto.response.ImportPreviewResponse.Warning;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportBatchStatus;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import com.sairo.be.domain.migration.repository.ImportBatchJdbcRepository;
import com.sairo.be.domain.migration.service.NormalizedRow.RowWarning;
import com.sairo.be.domain.migration.service.NormalizedRow.Severity;
import com.sairo.be.domain.migration.service.parser.ParsedRow;
import com.sairo.be.domain.property.service.PropertyImportService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!migrate")
@Service
public class ImportPreviewService {

  private static final int LOOKUP_CHUNK = 500;
  private static final int MAX_MATCHES_PER_ROW = 3;

  private final ImportGuard guard;
  private final ImportFileReader fileReader;
  private final ImportRowNormalizer normalizer;
  private final ImportDuplicateClassifier classifier;
  private final ImportBatchJdbcRepository jdbcRepository;
  private final CustomerImportService customerImportService;
  private final PropertyImportService propertyImportService;

  public ImportPreviewService(
      ImportGuard guard,
      ImportFileReader fileReader,
      ImportRowNormalizer normalizer,
      ImportDuplicateClassifier classifier,
      ImportBatchJdbcRepository jdbcRepository,
      CustomerImportService customerImportService,
      PropertyImportService propertyImportService) {
    this.guard = guard;
    this.fileReader = fileReader;
    this.normalizer = normalizer;
    this.classifier = classifier;
    this.jdbcRepository = jdbcRepository;
    this.customerImportService = customerImportService;
    this.propertyImportService = propertyImportService;
  }

  private record Computed(
      ParsedRow row,
      NormalizedRow normalized,
      List<RowWarning> warnings,
      ImportDuplicateClassifier.Judgement judgement,
      List<RelationMatch> matches) {

    Long existingTargetId() {
      return judgement.matchedEntityId();
    }
  }

  public ImportPreviewResponse preview(
      StaffPrincipal principal, Long batchId, Integer page, Integer size) {
    PageQuery pageQuery = PageQuery.of(page, size);
    ImportBatch batch = guard.find(principal.officeId(), batchId);
    guard.requireEditable(batch);
    if (batch.getStatus() == ImportBatchStatus.UPLOADED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    ImportLoadedData data = fileReader.loadWithLatestMapping(batch);
    ImportTargetType type = batch.getTargetType();
    String sourceSystem = data.mapping().getSourceSystem();
    List<Computed> resolved = resolveAll(principal.officeId(), batch, data);
    Summary summary = summaryOf(resolved);

    int from = Math.min(pageQuery.page() * pageQuery.size(), resolved.size());
    int to = Math.min(from + pageQuery.size(), resolved.size());
    List<PreviewRow> rows = new ArrayList<>();
    List<DuplicateWarning> duplicates = new ArrayList<>();
    String sourceType = type.name();
    for (Computed item : resolved.subList(from, to)) {
      rows.add(toRow(data, item));
      if (item.existingTargetId() != null) {
        duplicates.add(
            new DuplicateWarning(
                item.row().rowNumber(),
                sourceSystem,
                sourceType,
                item.normalized().displaySourceId(),
                item.existingTargetId(),
                item.judgement().reason().name(),
                item.judgement().matchedArchived()));
      }
    }
    jdbcRepository.markPreviewed(batchId);
    return new ImportPreviewResponse(
        rows, duplicates, summary, pageQuery.page(), pageQuery.size(), resolved.size());
  }

  public Summary summarize(Long officeId, ImportBatch batch, Long mappingVersionId) {
    ImportLoadedData data = fileReader.loadWithMapping(batch, mappingVersionId);
    return summaryOf(resolveAll(officeId, batch, data));
  }

  private List<Computed> resolveAll(Long officeId, ImportBatch batch, ImportLoadedData data) {
    ImportTargetType type = batch.getTargetType();
    String sourceSystem = data.mapping().getSourceSystem();
    Map<String, String> columnByField = data.columnByField();
    List<NormalizedRow> normalizedRows = new ArrayList<>();
    for (ParsedRow row : data.sheet().rows()) {
      normalizedRows.add(
          normalizer.normalize(
              type,
              data.rawByField(row),
              columnByField,
              ImportTargetFields.autoSourceId(batch.getId(), row.rowNumber())));
    }
    ImportDuplicateClassifier.Session session =
        classifier.open(officeId, type, sourceSystem, normalizedRows);
    String sourceIdField = ImportTargetFields.sourceIdField(type);
    Set<String> seenIds = new HashSet<>();
    List<Computed> resolved = new ArrayList<>(normalizedRows.size());
    List<ParsedRow> rows = data.sheet().rows();
    for (int index = 0; index < rows.size(); index++) {
      NormalizedRow normalized = normalizedRows.get(index);
      List<RowWarning> warnings = new ArrayList<>(normalized.warnings());
      String id = normalized.sourceId();
      if (id != null && !seenIds.add(id)) {
        warnings.add(
            new RowWarning(
                "DUPLICATE_SOURCE_ID_IN_FILE",
                sourceIdField,
                "같은 원본 ID가 앞선 줄에 있어 앞선 줄과 같은 레코드로 연결합니다.",
                Severity.WARNING));
      }
      ImportDuplicateClassifier.Judgement judgement = session.judge(normalized);
      if (!judgement.skipped() && !normalized.hasError()) {
        session.markCreated(normalized);
      }
      resolved.add(
          new Computed(rows.get(index), normalized, warnings, judgement, new ArrayList<>()));
    }
    attachRelations(officeId, type, columnByField, resolved);
    return resolved;
  }

  private static Summary summaryOf(List<Computed> resolved) {
    int errorRows = 0;
    int warningRows = 0;
    int newRows = 0;
    int alreadyImported = 0;
    int inFile = 0;
    int existing = 0;
    int relationRows = 0;
    for (Computed item : resolved) {
      boolean error = item.warnings().stream().anyMatch(w -> w.severity() == Severity.ERROR);
      if (error) {
        errorRows++;
      }
      if (item.warnings().stream().anyMatch(w -> w.severity() == Severity.WARNING)) {
        warningRows++;
      }
      if (item.judgement().skipped()) {
        switch (item.judgement().reason()) {
          case ALREADY_IMPORTED -> alreadyImported++;
          case DUPLICATE_IN_FILE -> inFile++;
          case DUPLICATE_EXISTING -> existing++;
        }
      } else if (!error) {
        newRows++;
      }
      if (!item.matches().isEmpty()) {
        relationRows++;
      }
    }
    return new Summary(
        resolved.size(),
        errorRows,
        warningRows,
        alreadyImported + inFile + existing,
        relationRows,
        newRows,
        alreadyImported,
        inFile,
        existing);
  }

  private PreviewRow toRow(ImportLoadedData data, Computed item) {
    Map<String, Object> values = new LinkedHashMap<>();
    item.normalized()
        .values()
        .forEach((field, value) -> values.put(field, value == null ? null : jsonValue(value)));
    return new PreviewRow(
        item.row().rowNumber(),
        data.sourceValues(item.row()),
        values,
        item.normalized().unitConversions().stream()
            .map(
                conversion ->
                    new UnitConversion(
                        conversion.field(),
                        conversion.sourceValue(),
                        jsonValue(conversion.targetValue()),
                        conversion.ruleCode()))
            .toList(),
        item.matches(),
        item.warnings().stream()
            .map(w -> new Warning(w.code(), w.field(), w.message(), w.severity().name()))
            .toList(),
        item.judgement().skipped() ? item.judgement().reason().name() : "NEW",
        item.judgement().matchedEntityId());
  }

  private static Object jsonValue(Object value) {
    if (value instanceof Number || value instanceof Boolean) {
      return value;
    }
    return value.toString();
  }

  private void attachRelations(
      Long officeId,
      ImportTargetType type,
      Map<String, String> columnByField,
      List<Computed> computed) {
    if (type == ImportTargetType.CUSTOMER) {
      attachCustomerRelations(officeId, columnByField, computed);
    } else if (type == ImportTargetType.PROPERTY) {
      attachPropertyRelations(officeId, columnByField, computed);
    }
  }

  private void attachCustomerRelations(
      Long officeId, Map<String, String> columnByField, List<Computed> computed) {
    Set<String> names = new LinkedHashSet<>();
    for (Computed item : computed) {
      String name = compact(item.normalized().values().get(ImportTargetFields.CUSTOMER_NAME));
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    Map<String, List<CustomerImportService.MatchCandidate>> byName = new HashMap<>();
    List<String> nameList = new ArrayList<>(names);
    for (int start = 0; start < nameList.size(); start += LOOKUP_CHUNK) {
      for (CustomerImportService.MatchCandidate candidate :
          customerImportService.findMatchCandidates(
              officeId, nameList.subList(start, Math.min(start + LOOKUP_CHUNK, nameList.size())))) {
        byName.computeIfAbsent(candidate.normalizedName(), key -> new ArrayList<>()).add(candidate);
      }
    }
    String column = columnByField.get(ImportTargetFields.CUSTOMER_NAME);
    for (Computed item : computed) {
      String name = compact(item.normalized().values().get(ImportTargetFields.CUSTOMER_NAME));
      String phone = digits(item.normalized().values().get(ImportTargetFields.CUSTOMER_PHONE));
      for (CustomerImportService.MatchCandidate candidate : byName.getOrDefault(name, List.of())) {
        if (item.matches().size() >= MAX_MATCHES_PER_ROW
            || (item.existingTargetId() != null
                && item.existingTargetId() == candidate.customerId())) {
          continue;
        }
        double confidence;
        if (!phone.isEmpty()
            && (phone.equals(candidate.phoneDigits())
                || phone.equals(candidate.secondaryPhoneDigits()))) {
          confidence = 0.9;
        } else if (phone.isEmpty()) {
          confidence = 0.5;
        } else {
          confidence = 0.4;
        }
        item.matches()
            .add(
                new RelationMatch(
                    column, ImportTargetType.CUSTOMER.name(), candidate.customerId(), confidence));
      }
    }
  }

  private void attachPropertyRelations(
      Long officeId, Map<String, String> columnByField, List<Computed> computed) {
    Set<String> addresses = new LinkedHashSet<>();
    for (Computed item : computed) {
      String address = compact(item.normalized().values().get(ImportTargetFields.PROPERTY_ADDRESS));
      if (!address.isEmpty()) {
        addresses.add(address);
      }
    }
    Map<String, List<PropertyImportService.MatchCandidate>> byAddress = new HashMap<>();
    List<String> addressList = new ArrayList<>(addresses);
    for (int start = 0; start < addressList.size(); start += LOOKUP_CHUNK) {
      for (PropertyImportService.MatchCandidate candidate :
          propertyImportService.findMatchCandidates(
              officeId,
              addressList.subList(start, Math.min(start + LOOKUP_CHUNK, addressList.size())))) {
        byAddress
            .computeIfAbsent(candidate.normalizedAddress(), key -> new ArrayList<>())
            .add(candidate);
      }
    }
    String column = columnByField.get(ImportTargetFields.PROPERTY_ADDRESS);
    for (Computed item : computed) {
      String address = compact(item.normalized().values().get(ImportTargetFields.PROPERTY_ADDRESS));
      String detail =
          compact(item.normalized().values().get(ImportTargetFields.PROPERTY_ADDRESS_DETAIL));
      for (PropertyImportService.MatchCandidate candidate :
          byAddress.getOrDefault(address, List.of())) {
        if (item.matches().size() >= MAX_MATCHES_PER_ROW
            || (item.existingTargetId() != null
                && item.existingTargetId() == candidate.propertyId())) {
          continue;
        }
        double confidence;
        if (detail.equals(candidate.normalizedAddressDetail())) {
          confidence = detail.isEmpty() ? 0.8 : 0.9;
        } else {
          confidence = 0.4;
        }
        item.matches()
            .add(
                new RelationMatch(
                    column, ImportTargetType.PROPERTY.name(), candidate.propertyId(), confidence));
      }
    }
  }

  private static String compact(Object value) {
    return value == null ? "" : value.toString().replaceAll("\\s+", "").toLowerCase();
  }

  private static String digits(Object value) {
    return value == null ? "" : value.toString().replaceAll("\\D", "");
  }
}
