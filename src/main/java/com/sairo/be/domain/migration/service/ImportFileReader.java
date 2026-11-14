package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportMappingItem;
import com.sairo.be.domain.migration.entity.ImportMappingVersion;
import com.sairo.be.domain.migration.repository.ImportMappingItemRepository;
import com.sairo.be.domain.migration.repository.ImportMappingVersionRepository;
import com.sairo.be.domain.migration.service.parser.ParsedSheet;
import com.sairo.be.domain.migration.service.parser.SpreadsheetParser;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.ObjectStorage;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ImportFileReader {

  private final ObjectStorage objectStorage;
  private final ImportMappingVersionRepository mappingVersionRepository;
  private final ImportMappingItemRepository mappingItemRepository;

  public ImportFileReader(
      ObjectStorage objectStorage,
      ImportMappingVersionRepository mappingVersionRepository,
      ImportMappingItemRepository mappingItemRepository) {
    this.objectStorage = objectStorage;
    this.mappingVersionRepository = mappingVersionRepository;
    this.mappingItemRepository = mappingItemRepository;
  }

  public ParsedSheet readSelectedSheet(ImportBatch batch) {
    byte[] content =
        objectStorage
            .find(batch.getFileObjectKey())
            .orElseThrow(() -> new IllegalStateException("업로드 원본 파일을 찾을 수 없습니다."));
    return SpreadsheetParser.parse(content, batch.getFileName(), batch.getSelectedSheetName())
        .find(batch.getSelectedSheetName())
        .orElseThrow(() -> new IllegalStateException("선택한 시트를 찾을 수 없습니다."));
  }

  public ImportLoadedData loadWithLatestMapping(ImportBatch batch) {
    ImportMappingVersion mapping =
        mappingVersionRepository
            .findFirstByImportBatchIdOrderByVersionNoDesc(batch.getId())
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    return load(batch, mapping);
  }

  public ImportLoadedData loadWithMapping(ImportBatch batch, Long mappingVersionId) {
    ImportMappingVersion mapping =
        mappingVersionRepository
            .findById(mappingVersionId)
            .filter(version -> version.getImportBatchId().equals(batch.getId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    return load(batch, mapping);
  }

  private ImportLoadedData load(ImportBatch batch, ImportMappingVersion mapping) {
    List<ImportMappingItem> items =
        mappingItemRepository.findByMappingVersionIdOrderById(mapping.getId());
    return new ImportLoadedData(batch, mapping, items, readSelectedSheet(batch));
  }
}
