package com.sairo.be.domain.migration.service.parser;

import java.util.List;

public record ParsedSheet(
    String name, int rowCount, int columnCount, List<String> headers, List<ParsedRow> rows) {}
