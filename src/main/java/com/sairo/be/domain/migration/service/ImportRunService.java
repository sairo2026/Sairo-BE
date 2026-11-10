package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.repository.ImportBatchRepository;
import com.sairo.be.domain.migration.repository.ImportRowResultRepository;
import com.sairo.be.domain.migration.service.parser.ParsedRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.security.StaffPrincipal;
import java.util.HashSet;
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

  public ImportRunService(
      ImportBatchRepository batchRepository,
      ImportRowResultRepository rowResultRepository,
      ImportFileReader fileReader,
      ImportRowNormalizer normalizer,
      ImportRunTransactions transactions) {
    this.batchRepository = batchRepository;
    this.rowResultRepository = rowResultRepository;
    this.fileReader = fileReader;
    this.normalizer = normalizer;
    this.transactions = transactions;
  }

  public void run(long batchId, StaffPrincipal principal, boolean retryOnly) {
    try {
      ImportBatch batch = batchRepository.findById(batchId).orElseThrow();
      ImportLoadedData data = fileReader.loadWithLatestMapping(batch);
      Set<Integer> skip =
          retryOnly
              ? new HashSet<>(rowResultRepository.findSucceededRowNumbers(batchId))
              : Set.of();
      String sourceSystem = data.mapping().getSourceSystem();
      for (ParsedRow row : data.sheet().rows()) {
        if (skip.contains(row.rowNumber())) {
          continue;
        }
        processRow(principal, data, batch, sourceSystem, row);
      }
      transactions.finish(batchId);
    } catch (RuntimeException e) {
      log.error("데이터 이전 실행이 중단됐습니다. batchId={}", batchId, e);
      transactions.markFailed(batchId);
    }
  }

  private void processRow(
      StaffPrincipal principal,
      ImportLoadedData data,
      ImportBatch batch,
      String sourceSystem,
      ParsedRow row) {
    NormalizedRow normalized =
        normalizer.normalize(batch.getTargetType(), data.rawByField(row), data.columnByField());
    if (normalized.hasError()) {
      transactions.recordFailure(batch, row.rowNumber(), truncate(normalized.errorSummary()));
      return;
    }
    try {
      transactions.writeRow(principal, batch, sourceSystem, normalized, row.rowNumber());
    } catch (BusinessException e) {
      transactions.recordFailure(batch, row.rowNumber(), truncate(describe(e)));
    } catch (RuntimeException e) {
      log.warn("데이터 이전 줄 처리에 실패했습니다. batchId={}, row={}", batch.getId(), row.rowNumber(), e);
      transactions.recordFailure(batch, row.rowNumber(), "저장 중 오류가 발생했습니다.");
    }
  }

  private static String describe(BusinessException exception) {
    String fields =
        exception.getFieldErrors().stream()
            .map(item -> item.field() + " " + item.code() + ": " + item.message())
            .collect(Collectors.joining(" / "));
    return exception.getErrorCode().name() + (fields.isEmpty() ? "" : " " + fields);
  }

  private static String truncate(String text) {
    String value = text == null || text.isBlank() ? "처리에 실패했습니다." : text;
    return value.length() > REASON_MAX ? value.substring(0, REASON_MAX) : value;
  }
}
