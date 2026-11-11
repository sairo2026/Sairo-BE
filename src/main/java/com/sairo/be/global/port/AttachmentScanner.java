package com.sairo.be.global.port;

public interface AttachmentScanner {

  enum Verdict {
    CLEAN,
    REJECTED
  }

  Verdict scan(String fileName, String mediaType, byte[] content);
}
