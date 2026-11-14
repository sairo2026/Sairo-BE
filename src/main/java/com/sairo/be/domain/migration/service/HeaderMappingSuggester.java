package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.entity.ImportTargetType;
import java.util.List;

public interface HeaderMappingSuggester {

  record Suggestion(String sourceColumn, String targetField, double confidence) {}

  List<Suggestion> suggest(ImportTargetType targetType, List<String> headers);
}
