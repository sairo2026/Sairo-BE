package com.sairo.be.domain.migration.dto.request;

import java.util.List;

public record ExportPolicyUpdateRequest(List<String> allowedTables) {}
