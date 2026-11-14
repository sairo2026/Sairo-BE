package com.sairo.be.domain.migration.dto.request;

public record ImportSupportCreateRequest(
    Long importBatchId, String sourceSystem, int estimatedRowCount) {}
