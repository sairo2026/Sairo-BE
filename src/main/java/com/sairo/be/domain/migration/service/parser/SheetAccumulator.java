package com.sairo.be.domain.migration.service.parser;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SheetAccumulator {

  private static final int HEADER_MAX_LENGTH = 200;

  private final String name;
  private final boolean keepRows;
  private final List<ParsedRow> rawRows = new ArrayList<>();
  private List<String> headerCells;
  private int dataRowCount;
  private int columnCount;

  SheetAccumulator(String name, boolean keepRows) {
    this.name = name;
    this.keepRows = keepRows;
  }

  void addRow(int rowNumber, List<String> rawCells) {
    List<String> cells = new ArrayList<>(rawCells.size());
    int lastIndex = -1;
    for (int i = 0; i < rawCells.size(); i++) {
      String value = clean(rawCells.get(i));
      cells.add(value);
      if (value != null) {
        lastIndex = i;
      }
    }
    if (lastIndex < 0) {
      return;
    }
    if (lastIndex >= SpreadsheetParser.MAX_COLUMNS) {
      throw new BusinessException(ErrorCode.IMPORT_LIMIT_EXCEEDED);
    }
    columnCount = Math.max(columnCount, lastIndex + 1);
    List<String> trimmed = new ArrayList<>(cells.subList(0, lastIndex + 1));
    if (headerCells == null) {
      headerCells = trimmed;
      return;
    }
    dataRowCount++;
    if (dataRowCount > SpreadsheetParser.MAX_ROWS) {
      throw new BusinessException(ErrorCode.IMPORT_LIMIT_EXCEEDED);
    }
    if (keepRows) {
      rawRows.add(new ParsedRow(rowNumber, trimmed));
    }
  }

  ParsedSheet finish() {
    if (headerCells == null) {
      return null;
    }
    List<String> headers = uniqueHeaders();
    List<ParsedRow> rows = new ArrayList<>(rawRows.size());
    for (ParsedRow raw : rawRows) {
      List<String> padded = new ArrayList<>(columnCount);
      for (int i = 0; i < columnCount; i++) {
        padded.add(raw.cell(i));
      }
      rows.add(new ParsedRow(raw.rowNumber(), padded));
    }
    return new ParsedSheet(name, dataRowCount, columnCount, headers, rows);
  }

  private List<String> uniqueHeaders() {
    List<String> headers = new ArrayList<>(columnCount);
    Set<String> used = new HashSet<>();
    for (int i = 0; i < columnCount; i++) {
      String base = i < headerCells.size() ? headerCells.get(i) : null;
      if (base == null) {
        base = "열" + (i + 1);
      }
      if (base.length() > HEADER_MAX_LENGTH - 6) {
        base = base.substring(0, HEADER_MAX_LENGTH - 6);
      }
      String candidate = base;
      int suffix = 2;
      while (!used.add(candidate)) {
        candidate = base + " (" + suffix++ + ")";
      }
      headers.add(candidate);
    }
    return headers;
  }

  private static String clean(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.replace(' ', ' ').strip();
    return stripped.isEmpty() ? null : stripped;
  }
}
