package com.sairo.be.domain.migration.service.parser;

import java.util.List;

public record ParsedRow(int rowNumber, List<String> cells) {

  public String cell(int index) {
    return index >= 0 && index < cells.size() ? cells.get(index) : null;
  }
}
