package com.sairo.be.domain.migration.service.parser;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

final class CsvSheetReader {

  static final String SHEET_NAME = "Sheet1";

  private CsvSheetReader() {}

  static void read(byte[] content, SheetCollector collector) throws IOException {
    String text = decode(content);
    CSVFormat format =
        CSVFormat.DEFAULT
            .builder()
            .setDelimiter(detectDelimiter(text))
            .setIgnoreEmptyLines(false)
            .setTrim(false)
            .get();
    SheetAccumulator accumulator = collector.start(SHEET_NAME);
    try (CSVParser parser = format.parse(new StringReader(text))) {
      for (CSVRecord record : parser) {
        List<String> cells = new ArrayList<>(record.size());
        for (String value : record) {
          cells.add(value);
        }
        accumulator.addRow((int) record.getRecordNumber(), cells);
      }
    }
    collector.finish(accumulator);
  }

  private static String decode(byte[] content) {
    int offset = 0;
    if (content.length >= 3
        && (content[0] & 0xFF) == 0xEF
        && (content[1] & 0xFF) == 0xBB
        && (content[2] & 0xFF) == 0xBF) {
      offset = 3;
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content, offset, content.length - offset))
          .toString();
    } catch (CharacterCodingException e) {
      return new String(content, offset, content.length - offset, legacyKoreanCharset());
    }
  }

  private static Charset legacyKoreanCharset() {
    for (String name : List.of("x-windows-949", "EUC-KR")) {
      if (Charset.isSupported(name)) {
        return Charset.forName(name);
      }
    }
    return StandardCharsets.ISO_8859_1;
  }

  private static char detectDelimiter(String text) {
    int end = text.indexOf('\n');
    String first = end < 0 ? text : text.substring(0, end);
    char best = ',';
    int bestCount = count(first, ',');
    for (char candidate : new char[] {'\t', ';'}) {
      int count = count(first, candidate);
      if (count > bestCount) {
        best = candidate;
        bestCount = count;
      }
    }
    return best;
  }

  private static int count(String text, char target) {
    boolean quoted = false;
    int count = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (c == target && !quoted) {
        count++;
      }
    }
    return count;
  }
}
