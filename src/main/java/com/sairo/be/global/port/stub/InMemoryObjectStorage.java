package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.ObjectStorage;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!prod & !migrate")
@Component
public class InMemoryObjectStorage implements ObjectStorage {

  private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

  @Override
  public void put(String key, byte[] content, String contentType) {
    objects.put(key, content.clone());
  }

  @Override
  public Optional<byte[]> find(String key) {
    return Optional.ofNullable(objects.get(key)).map(byte[]::clone);
  }

  @Override
  public URI issueDownloadUrl(String key, Duration timeToLive) {
    if (!objects.containsKey(key)) {
      throw new IllegalArgumentException("존재하지 않는 객체입니다.");
    }
    return URI.create("https://storage.stub.invalid/" + key + "?ttl=" + timeToLive.toSeconds());
  }

  @Override
  public void delete(String key) {
    objects.remove(key);
  }
}
