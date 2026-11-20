package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.repository.ImportBatchRepository;
import com.sairo.be.domain.migration.repository.ImportRowCorrectionJdbcRepository;
import com.sairo.be.domain.migration.repository.ImportRowResultRepository;
import com.sairo.be.domain.migration.service.parser.ParsedRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.security.StaffPrincipal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Slf4j
@Profile("!migrate")
@Service
public class ImportRunService {

  private static final int REASON_MAX = 1_000;

  private final ImportBatchRepository batchRepository;
  private final ImportRowResultRepository rowResultRepository;
  private final ImportFileReader fileReader;
  private final ImportRowNormalizer normalizer;
  private final ImportRunTransactions transactions;
  private final ImportDuplicateClassifier classifier;
  private final ImportRowCorrectionJdbcRepository correctionRepository;

  public ImportRunService(
      ImportBatchRepository batchRepository,
      ImportRowResultRepository rowResultRepository,
      ImportFileReader fileReader,
      ImportRowNormalizer normalizer,
      ImportRunTransactions transactions,
      ImportDuplicateClassifier classifier,
      ImportRowCorrectionJdbcRepository correctionRepository) {
    this.batchRepository = batchRepository;
    this.rowResultRepository = rowResultRepository;
    this.fileReader = fileReader;
    this.normalizer = normalizer;
    this.transactions = transactions;
    this.classifier = classifier;
    this.correctionRepository = correctionRepository;
  }

  public void run(long batchId, StaffPrincipal principal, boolean retryOnly) {
    run(batchId, principal, retryOnly, null);
  }

  public void run(
      long batchId, StaffPrincipal principal, boolean retryOnly, Long mappingVersionId) {
    try {
      ImportBatch batch = batchRepository.findById(batchId).orElseThrow();
      ImportLoadedData data =
          mappingVersionId == null
              ? fileReader.loadWithLatestMapping(batch)
              : fileReader.loadWithMapping(batch, mappingVersionId);
      Set<Integer> skip =
          retryOnly ? new HashSet<>(rowResultRepository.findFinishedRowNumbers(batchId)) : Set.of();
      String sourceSystem = data.mapping().getSourceSystem();
      List<ParsedRow> rows = data.sheet().rows();
      List<NormalizedRow> normalizedRows = new ArrayList<>(rows.size());
      Map<Integer, Map<String, String>> corrections = correctionRepository.findByBatch(batchId);
      for (ParsedRow row : rows) {
        Map<String, String> raw = new LinkedHashMap<>(data.rawByField(row));
        raw.putAll(corrections.getOrDefault(row.rowNumber(), Map.of()));
        normalizedRows.add(
            normalizer.normalize(
                batch.getTargetType(),
                raw,
                data.columnByField(),
                ImportTargetFields.autoSourceId(batch.getId(), row.rowNumber())));
      }
      ImportDuplicateClassifier.Session session =
          classifier.open(batch.getOfficeId(), batch.getTargetType(), sourceSystem, normalizedRows);
      for (int index = 0; index < rows.size(); index++) {
        ParsedRow row = rows.get(index);
        if (skip.contains(row.rowNumber())) {
          continue;
        }
        processRow(principal, batch, sourceSystem, row, normalizedRows.get(index), session);
      }
      transactions.finish(batchId);
    } catch (RuntimeException e) {
      log.error("데이터 이전 실행이 중단됐습니다. batchId={}", batchId, e);
      transactions.markFailed(batchId);
    }
  }

  private void processRow(
      StaffPrincipal principal,
      ImportBatch batch,
      String sourceSystem,
      ParsedRow row,
      NormalizedRow normalized,
      ImportDuplicateClassifier.Session session) {
    if (normalized.hasError()) {
      transactions.recordFailure(batch, row.rowNumber(), truncate(normalized.errorSummary()));
      return;
    }
    ImportDuplicateClassifier.Judgement judgement = session.judge(normalized);
    if (judgement.skipped()) {
      transactions.recordSkipped(
          batch, row.rowNumber(), judgement.reason(), judgement.matchedEntityId());
      return;
    }
    try {
      ImportRunTransactions.RowDisposition disposition =
          transactions.writeRow(principal, batch, sourceSystem, normalized, row.rowNumber());
      if (disposition == ImportRunTransactions.RowDisposition.CREATED) {
        session.markCreated(normalized);
      }
    } catch (BusinessException e) {
      transactions.recordFailure(batch, row.rowNumber(), truncate(describe(e)));
    } catch (RuntimeException e) {
      log.warn("데이터 이전 줄 처리에 실패했습니다. batchId={}, row={}", batch.getId(), row.rowNumber(), e);
      transactions.recordFailure(batch, row.rowNumber(), "SAVE_ERROR: 저장 중 오류가 발생했습니다.");
    }
  }

  private static String describe(BusinessException exception) {
    String fields =
        exception.getFieldErrors().stream()
            .map(item -> item.code() + "(" + item.field() + "): " + item.message())
            .collect(Collectors.joining(" / "));
    return fields.isEmpty() ? exception.getErrorCode().name() + ": 저장할 수 없는 값입니다." : fields;
  }

  private static String truncate(String text) {
    String value = text == null || text.isBlank() ? "처리에 실패했습니다." : text;
    return value.length() > REASON_MAX ? value.substring(0, REASON_MAX) : value;
  }
}
