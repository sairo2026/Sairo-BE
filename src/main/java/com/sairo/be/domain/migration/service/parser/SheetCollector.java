package com.sairo.be.domain.migration.service.parser;

import java.util.ArrayList;
import java.util.List;

final class SheetCollector {

  private final String keepSheet;
  private final List<ParsedSheet> sheets = new ArrayList<>();

  SheetCollector(String keepSheet) {
    this.keepSheet = keepSheet;
  }

  SheetAccumulator start(String name) {
    return new SheetAccumulator(name, keepSheet != null && keepSheet.equals(name));
  }

  void finish(SheetAccumulator accumulator) {
    ParsedSheet sheet = accumulator.finish();
    if (sheet != null) {
      sheets.add(sheet);
    }
  }

  List<ParsedSheet> sheets() {
    return sheets;
  }
}
