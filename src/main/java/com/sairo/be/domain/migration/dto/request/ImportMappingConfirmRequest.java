package com.sairo.be.domain.migration.dto.request;

import java.util.List;

public record ImportMappingConfirmRequest(List<Mapping> mappings, String sourceSystem) {

  public record Mapping(String sourceColumn, String targetField) {}
}
