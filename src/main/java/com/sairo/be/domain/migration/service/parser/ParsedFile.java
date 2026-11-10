package com.sairo.be.domain.migration.service.parser;

import java.util.List;
import java.util.Optional;

public record ParsedFile(List<ParsedSheet> sheets) {

  public Optional<ParsedSheet> find(String name) {
    return sheets.stream().filter(sheet -> sheet.name().equals(name)).findFirst();
  }
}
