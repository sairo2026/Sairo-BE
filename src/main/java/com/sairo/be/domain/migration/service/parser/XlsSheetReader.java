package com.sairo.be.domain.migration.service.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

final class XlsSheetReader {

  private XlsSheetReader() {}

  static void read(byte[] content, SheetCollector collector) throws IOException {
    try (POIFSFileSystem fileSystem = new POIFSFileSystem(new ByteArrayInputStream(content));
        HSSFWorkbook workbook = new HSSFWorkbook(fileSystem)) {
      for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
        Sheet sheet = workbook.getSheetAt(index);
        SheetAccumulator accumulator = collector.start(sheet.getSheetName());
        for (Row row : sheet) {
          List<String> cells = new ArrayList<>();
          int last = Math.max(row.getLastCellNum(), 0);
          for (int column = 0; column < last; column++) {
            cells.add(value(row.getCell(column)));
          }
          accumulator.addRow(row.getRowNum() + 1, cells);
        }
        collector.finish(accumulator);
      }
    }
  }

  private static String value(Cell cell) {
    if (cell == null) {
      return null;
    }
    CellType type =
        cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType()
            : cell.getCellType();
    return switch (type) {
      case STRING -> cell.getStringCellValue();
      case NUMERIC ->
          DateUtil.isCellDateFormatted(cell)
              ? CellValues.date(cell.getNumericCellValue())
              : CellValues.plain(cell.getNumericCellValue());
      case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
      default -> null;
    };
  }
}
