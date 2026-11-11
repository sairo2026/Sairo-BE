package com.sairo.be.domain.migration.dto.request;

import java.util.List;

public record ExportCreateRequest(String scope, List<String> tables) {}
