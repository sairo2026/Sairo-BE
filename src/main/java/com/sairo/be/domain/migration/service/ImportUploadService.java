package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.dto.request.ImportSheetSelectRequest;
import com.sairo.be.domain.migration.dto.response.ImportBatchStatusResponse;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportSheetInfo;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import com.sairo.be.domain.migration.mapper.ImportBatchMapper;
import com.sairo.be.domain.migration.repository.ImportBatchJdbcRepository;
import com.sairo.be.domain.migration.repository.ImportBatchRepository;
import com.sairo.be.domain.migration.service.parser.ParsedFile;
import com.sairo.be.domain.migration.service.parser.SpreadsheetParser;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.StaffPrincipal;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ImportUploadService {

  private static final int FILE_NAME_MAX = 255;
  private static final List<ImportTargetType> UPLOADABLE_TARGET_TYPES =
      List.of(ImportTargetType.CUSTOMER, ImportTargetType.PROPERTY);

  private final ImportBatchRepository batchRepository;
  private final ImportBatchJdbcRepository jdbcRepository;
  private final ImportGuard guard;
  private final ObjectStorage objectStorage;

  public ImportUploadService(
      ImportBatchRepository batchRepository,
      ImportBatchJdbcRepository jdbcRepository,
      ImportGuard guard,
      ObjectStorage objectStorage) {
    this.batchRepository = batchRepository;
    this.jdbcRepository = jdbcRepository;
    this.guard = guard;
    this.objectStorage = objectStorage;
  }

  @Transactional
  public ImportBatchStatusResponse upload(
      StaffPrincipal principal, MultipartFile file, String targetTypeInput) {
    ImportTargetType targetType = parseTargetType(targetTypeInput);
    if (file == null || file.isEmpty()) {
      throw ImportRequestParser.invalid("file", FieldErrorCode.REQUIRED, "파일이 필요합니다.");
    }
    if (file.getSize() > SpreadsheetParser.MAX_BYTES) {
      throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
    }
    byte[] content = read(file);
    String fileName = fileName(file);
    ParsedFile parsed = SpreadsheetParser.parse(content, fileName, null);
    List<ImportSheetInfo> sheets =
        parsed.sheets().stream()
            .map(sheet -> new ImportSheetInfo(sheet.name(), sheet.rowCount(), sheet.columnCount()))
            .toList();
    String key =
        "import-batches/" + principal.officeId() + "/" + UUID.randomUUID() + extension(fileName);
    objectStorage.put(key, content, contentType(fileName));
    try {
      ImportBatch batch =
          batchRepository.saveAndFlush(
              ImportBatch.upload(
                  principal.officeId(),
                  principal.membershipId(),
                  targetType,
                  key,
                  fileName,
                  sheets.get(0).name()));
      jdbcRepository.saveSheets(batch.getId(), sheets);
      return ImportBatchMapper.toStatus(batch, sheets);
    } catch (RuntimeException e) {
      objectStorage.delete(key);
      throw e;
    }
  }

  @Transactional
  public ImportBatchStatusResponse selectSheet(
      StaffPrincipal principal, Long batchId, long expectedVersion, JsonNode body) {
    ImportSheetSelectRequest request = ImportRequestParser.sheetSelect(body);
    ImportBatch batch = guard.lock(principal.officeId(), batchId);
    guard.requireNotManagedBySupport(batchId);
    guard.requireEditable(batch);
    guard.requireVersion(batch, expectedVersion);
    List<ImportSheetInfo> sheets = jdbcRepository.findSheets(batchId);
    boolean known = sheets.stream().anyMatch(sheet -> sheet.name().equals(request.sheetName()));
    if (!known) {
      throw ImportRequestParser.invalid(
          "sheetName", FieldErrorCode.NOT_ALLOWED, "업로드한 파일에 없는 시트입니다.");
    }
    if (request.sheetName().equals(batch.getSelectedSheetName())) {
      return ImportBatchMapper.toStatus(batch, sheets);
    }
    batch.selectSheet(request.sheetName());
    ImportBatch saved = batchRepository.saveAndFlush(batch);
    return ImportBatchMapper.toStatus(saved, sheets);
  }

  private static ImportTargetType parseTargetType(String input) {
    if (input == null || input.isBlank()) {
      throw ImportRequestParser.invalid("targetType", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    ImportTargetType targetType;
    try {
      targetType = ImportTargetType.valueOf(input.strip());
    } catch (IllegalArgumentException e) {
      throw ImportRequestParser.invalid("targetType", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
    if (!UPLOADABLE_TARGET_TYPES.contains(targetType)) {
      throw ImportRequestParser.invalid("targetType", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
    return targetType;
  }

  private static byte[] read(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw ImportRequestParser.invalid("file", FieldErrorCode.INVALID_FORMAT, "파일을 읽을 수 없습니다.");
    }
  }

  private static String fileName(MultipartFile file) {
    String original = file.getOriginalFilename();
    if (original == null || original.isBlank()) {
      return "upload";
    }
    String name = original.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).strip();
    if (name.length() > FILE_NAME_MAX) {
      String extension = extension(name);
      name = name.substring(0, FILE_NAME_MAX - extension.length()) + extension;
    }
    return name.isEmpty() ? "upload" : name;
  }

  private static String extension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
  }

  private static String contentType(String fileName) {
    return switch (extension(fileName)) {
      case ".csv" -> "text/csv";
      case ".xls" -> "application/vnd.ms-excel";
      default -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    };
  }
}
