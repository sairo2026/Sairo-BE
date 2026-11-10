package com.sairo.be.global.security.access;

public interface AccessStage {

  AccessStageType type();

  AccessStageResult evaluate(AccessRequest request);

  static AccessStage passThrough(AccessStageType type) {
    return new AccessStage() {
      @Override
      public AccessStageType type() {
        return type;
      }

      @Override
      public AccessStageResult evaluate(AccessRequest request) {
        return AccessStageResult.allow();
      }
    };
  }
}
