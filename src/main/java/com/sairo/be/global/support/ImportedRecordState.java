package com.sairo.be.global.support;

public record ImportedRecordState(boolean exists, ImportedRecordBlocker blocker) {

  public static ImportedRecordState missing() {
    return new ImportedRecordState(false, null);
  }

  public static ImportedRecordState removable() {
    return new ImportedRecordState(true, null);
  }

  public static ImportedRecordState blockedBy(ImportedRecordBlocker blocker) {
    return new ImportedRecordState(true, blocker);
  }

  public boolean isRemovable() {
    return exists && blocker == null;
  }
}
