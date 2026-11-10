package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportMappingItem;
import com.sairo.be.domain.migration.entity.ImportMappingVersion;
import com.sairo.be.domain.migration.service.parser.ParsedRow;
import com.sairo.be.domain.migration.service.parser.ParsedSheet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ImportLoadedData(
    ImportBatch batch,
    ImportMappingVersion mapping,
    List<ImportMappingItem> items,
    ParsedSheet sheet) {

  public Map<String, String> columnByField() {
    Map<String, String> result = new LinkedHashMap<>();
    for (ImportMappingItem item : items) {
      result.put(item.getTargetField(), item.getSourceColumn());
    }
    return result;
  }

  public Map<String, String> rawByField(ParsedRow row) {
    Map<String, String> result = new LinkedHashMap<>();
    for (ImportMappingItem item : items) {
      int index = sheet.headers().indexOf(item.getSourceColumn());
      if (index >= 0) {
        result.put(item.getTargetField(), row.cell(index));
      }
    }
    return result;
  }

  public Map<String, String> sourceValues(ParsedRow row) {
    Map<String, String> result = new LinkedHashMap<>();
    for (int index = 0; index < sheet.headers().size(); index++) {
      result.put(sheet.headers().get(index), row.cell(index));
    }
    return result;
  }
}
