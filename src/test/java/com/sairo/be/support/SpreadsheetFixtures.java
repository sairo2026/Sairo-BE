package com.sairo.be.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class SpreadsheetFixtures {

  private SpreadsheetFixtures() {}

  public static Map<String, List<List<Object>>> sheets(Object... nameAndRows) {
    Map<String, List<List<Object>>> sheets = new LinkedHashMap<>();
    for (int i = 0; i < nameAndRows.length; i += 2) {
      @SuppressWarnings("unchecked")
      List<List<Object>> rows = (List<List<Object>>) nameAndRows[i + 1];
      sheets.put((String) nameAndRows[i], rows);
    }
    return sheets;
  }

  public static List<Object> row(Object... cells) {
    return java.util.Arrays.asList(cells);
  }

  public static byte[] csv(String... lines) {
    return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
  }

  public static byte[] csv(Charset charset, String... lines) {
    return String.join("\n", lines).getBytes(charset);
  }

  public static byte[] xlsx(Map<String, List<List<Object>>> sheets) {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      return write(workbook, sheets);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static byte[] xls(Map<String, List<List<Object>>> sheets) {
    try (HSSFWorkbook workbook = new HSSFWorkbook()) {
      return write(workbook, sheets);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static byte[] encryptedOle2() {
    try (POIFSFileSystem fileSystem = new POIFSFileSystem();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      fileSystem.createDocument(new ByteArrayInputStream(new byte[16]), "EncryptionInfo");
      fileSystem.createDocument(new ByteArrayInputStream(new byte[16]), "EncryptedPackage");
      fileSystem.writeFilesystem(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static byte[] csvWithRows(int rows, int columns) {
    StringBuilder builder = new StringBuilder();
    for (int c = 0; c < columns; c++) {
      builder.append(c == 0 ? "" : ",").append("c").append(c);
    }
    for (int r = 0; r < rows; r++) {
      builder.append('\n');
      for (int c = 0; c < columns; c++) {
        builder.append(c == 0 ? "" : ",").append("v").append(r);
      }
    }
    return builder.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] write(Workbook workbook, Map<String, List<List<Object>>> sheets)
      throws IOException {
    CellStyle dateStyle = workbook.createCellStyle();
    dateStyle.setDataFormat(
        workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
    for (Map.Entry<String, List<List<Object>>> entry : sheets.entrySet()) {
      Sheet sheet = workbook.createSheet(entry.getKey());
      int rowIndex = 0;
      for (List<Object> cells : entry.getValue()) {
        Row row = sheet.createRow(rowIndex++);
        for (int column = 0; column < cells.size(); column++) {
          Object value = cells.get(column);
          if (value == null) {
            continue;
          }
          Cell cell = row.createCell(column);
          if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
          } else if (value instanceof Boolean flag) {
            cell.setCellValue(flag);
          } else if (value instanceof LocalDate date) {
            cell.setCellValue(date);
            cell.setCellStyle(dateStyle);
          } else if (value instanceof Formula formula) {
            cell.setCellFormula(formula.expression());
          } else {
            cell.setCellValue(value.toString());
          }
        }
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    workbook.write(out);
    return out.toByteArray();
  }

  public record Formula(String expression) {}
}
