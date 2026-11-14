package com.sairo.be.global.port;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

public interface ObjectStorage {

  void put(String key, byte[] content, String contentType);

  Optional<byte[]> find(String key);

  URI issueDownloadUrl(String key, Duration timeToLive);

  void delete(String key);
}
