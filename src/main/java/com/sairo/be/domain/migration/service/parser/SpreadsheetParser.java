package com.sairo.be.domain.migration.service.parser;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

public final class SpreadsheetParser {

  public static final int MAX_ROWS = 10_000;
  public static final int MAX_COLUMNS = 100;
  public static final long MAX_BYTES = 20L * 1024 * 1024;

  private static final int NUL_SCAN_LIMIT = 64 * 1024;

  private enum Format {
    CSV,
    XLS,
    XLSX
  }

  private SpreadsheetParser() {}

  public static ParsedFile parse(byte[] content, String fileName, String keepSheet) {
    Format format = detect(content, fileName);
    SheetCollector collector = new SheetCollector(keepSheet);
    try {
      switch (format) {
        case CSV -> CsvSheetReader.read(content, collector);
        case XLSX -> XlsxSheetReader.read(content, collector);
        case XLS -> XlsSheetReader.read(content, collector);
      }
    } catch (BusinessException e) {
      throw e;
    } catch (EncryptedDocumentException e) {
      throw new BusinessException(ErrorCode.ENCRYPTED_FILE_REJECTED);
    } catch (Exception | StackOverflowError e) {
      throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
    if (collector.sheets().isEmpty()) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(new FieldErrorItem("file", FieldErrorCode.INVALID_VALUE, "읽을 수 있는 데이터가 없습니다.")));
    }
    return new ParsedFile(List.copyOf(collector.sheets()));
  }

  private static Format detect(byte[] content, String fileName) {
    String extension = extensionOf(fileName);
    boolean zip = startsWith(content, 0x50, 0x4B, 0x03, 0x04);
    boolean ole = startsWith(content, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
    switch (extension) {
      case "xlsx" -> {
        if (zip) {
          return Format.XLSX;
        }
        if (ole) {
          rejectEncrypted(content);
        }
        throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
      }
      case "xls" -> {
        if (ole) {
          rejectEncrypted(content);
          return Format.XLS;
        }
        throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
      }
      case "csv" -> {
        if (zip || ole || containsNul(content)) {
          throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        return Format.CSV;
      }
      default -> throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
  }

  private static void rejectEncrypted(byte[] content) {
    try (POIFSFileSystem fileSystem = new POIFSFileSystem(new ByteArrayInputStream(content))) {
      if (fileSystem.getRoot().hasEntry("EncryptedPackage")
          || fileSystem.getRoot().hasEntry("EncryptionInfo")) {
        throw new BusinessException(ErrorCode.ENCRYPTED_FILE_REJECTED);
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof BusinessException business) {
        throw business;
      }
      throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
    }
  }

  private static String extensionOf(String fileName) {
    if (fileName == null) {
      return "";
    }
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static boolean startsWith(byte[] content, int... signature) {
    if (content.length < signature.length) {
      return false;
    }
    for (int i = 0; i < signature.length; i++) {
      if ((content[i] & 0xFF) != signature[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsNul(byte[] content) {
    int limit = Math.min(content.length, NUL_SCAN_LIMIT);
    for (int i = 0; i < limit; i++) {
      if (content[i] == 0) {
        return true;
      }
    }
    return false;
  }
}
