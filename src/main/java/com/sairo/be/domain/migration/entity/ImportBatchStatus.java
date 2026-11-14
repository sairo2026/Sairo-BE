package com.sairo.be.domain.migration.entity;

public enum ImportBatchStatus {
  UPLOADED,
  MAPPING,
  PREVIEW,
  EXECUTING,
  COMPLETED,
  FAILED,
  CANCELLED;

  public boolean isEditable() {
    return this == UPLOADED || this == MAPPING || this == PREVIEW;
  }
}
