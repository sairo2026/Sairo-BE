package com.sairo.be.domain.migration.service.parser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

final class XlsxSheetReader {

  private static final DataFormatter RAW_FORMATTER = new RawValueFormatter();

  private XlsxSheetReader() {}

  static void read(byte[] content, SheetCollector collector) throws Exception {
    try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(content))) {
      ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
      XSSFReader reader = new XSSFReader(pkg);
      StylesTable styles = reader.getStylesTable();
      XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
      while (sheets.hasNext()) {
        try (InputStream stream = sheets.next()) {
          SheetAccumulator accumulator = collector.start(sheets.getSheetName());
          XMLReader parser = XMLHelper.newXMLReader();
          parser.setContentHandler(
              new XSSFSheetXMLHandler(
                  styles, strings, new RowCollector(accumulator), RAW_FORMATTER, false));
          parser.parse(new InputSource(stream));
          collector.finish(accumulator);
        }
      }
    }
  }

  private static final class RawValueFormatter extends DataFormatter {

    @Override
    public String formatRawCellContents(double value, int formatIndex, String formatString) {
      if (DateUtil.isADateFormat(formatIndex, formatString)) {
        return CellValues.date(value);
      }
      return CellValues.plain(value);
    }
  }

  private static final class RowCollector implements SheetContentsHandler {

    private final SheetAccumulator accumulator;
    private final List<String> cells = new ArrayList<>();

    private RowCollector(SheetAccumulator accumulator) {
      this.accumulator = accumulator;
    }

    @Override
    public void startRow(int rowNum) {
      cells.clear();
    }

    @Override
    public void endRow(int rowNum) {
      accumulator.addRow(rowNum + 1, new ArrayList<>(cells));
    }

    @Override
    public void cell(String cellReference, String formattedValue, XSSFComment comment) {
      if (formattedValue == null) {
        return;
      }
      int column = cellReference == null ? cells.size() : new CellReference(cellReference).getCol();
      while (cells.size() <= column) {
        cells.add(null);
      }
      cells.set(column, formattedValue);
    }
  }
}
